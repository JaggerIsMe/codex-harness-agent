package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.entity.enums.TurnEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "codex.smoke", matches = "true")
class CodexAppServerSmokeTest {
    @Test
    @EnabledIfSystemProperty(named = "codex.turn.smoke", matches = "true")
    void resumesThreadCreatedBeforeAnySuccessfulTurn(@TempDir Path workspace, @TempDir Path agentData) throws Exception {
        var properties = new AgentProperties();
        properties.setCodexRequestTimeoutSeconds(45);
        String threadId;
        try (var adapter = new AppServerCodexAdapter(properties, new ObjectMapper())) {
            threadId = adapter.startThread(new CodexThreadOptions("empty-thread-smoke", workspace, null));
        }
        try (var adapter = new AppServerCodexAdapter(properties, new ObjectMapper())) {
            var allowed = new com.myharness.agent.config.WorkspaceProperties();
            allowed.setName("empty");allowed.setPath(workspace);
            properties.setWorkspaces(List.of(allowed));properties.setDataDir(agentData);
            var registry = new com.myharness.agent.workspace.WorkspaceRegistry(properties,new ObjectMapper());
            var bus = new com.myharness.agent.connection.AgentEventBus();
            var events = new java.util.concurrent.CopyOnWriteArrayList<com.myharness.agent.connection.AgentEvent>();
            bus.subscribe(events::add);
            var manager = new AgentSessionManager(adapter,registry,bus,properties, new com.myharness.agent.attachment.ConversationAttachmentService(properties,null,registry,new ObjectMapper()), org.mockito.Mockito.mock(com.myharness.agent.artifact.ConversationArtifactService.class));
            var turn = new com.myharness.agent.entity.dto.StartTurnCommandDTO();
            turn.setProjectId("empty-thread-smoke");turn.setWorkspaceName("empty");turn.setConversationId("1");
            turn.setTurnId("1");turn.setCodexThreadId(threadId);turn.setMessage("Reply exactly HARNESS_OK without using tools.");
            org.junit.jupiter.api.Assertions.assertThrows(CodexThreadNotLoadedException.class,()->manager.startTurn(turn));
            turn.setRecreateUnstartedThread(true);
            var finished=new CompletableFuture<String>();var answer=new StringBuffer();
            bus.subscribe(event->{
                if(event.getType()==com.myharness.agent.entity.enums.AgentEventType.TURN_EVENT) {
                    var value=(com.myharness.agent.entity.dto.TurnEventDTO)event.getPayload();
                    if(value.getEventType()==TurnEventType.AGENT_MESSAGE_DELTA) answer.append(value.getContent());
                }
                if(event.getType()==com.myharness.agent.entity.enums.AgentEventType.TURN_COMPLETED) finished.complete(answer.toString());
                if(event.getType()==com.myharness.agent.entity.enums.AgentEventType.TURN_FAILED) finished.completeExceptionally(new AssertionError("Recreated thread failed"));
            });
            manager.startTurn(turn);
            var rebound=(com.myharness.agent.entity.dto.ThreadStartedEventDTO)events.get(0).getPayload();
            org.junit.jupiter.api.Assertions.assertNotEquals(threadId,rebound.getCodexThreadId());
            assertTrue(finished.get(90,TimeUnit.SECONDS).contains("HARNESS_OK"));
        }
    }
    @Test
    @EnabledIfSystemProperty(named = "codex.turn.smoke", matches = "true")
    void repliesToUserAfterStartingAndResumingRestrictedThread(@TempDir Path workspace) throws Exception {
        AgentProperties properties = new AgentProperties();
        properties.setCodexRequestTimeoutSeconds(45);
        String threadId;
        try (var adapter = new AppServerCodexAdapter(properties, new ObjectMapper())) {
            threadId = adapter.startThread(new CodexThreadOptions("turn-smoke", workspace, null));
            assertReply(adapter, threadId);
        }
        try (var adapter = new AppServerCodexAdapter(properties, new ObjectMapper())) {
            adapter.resumeThread(threadId, new CodexThreadOptions("turn-smoke", workspace, null));
            assertReply(adapter, threadId);
        }
    }

    private void assertReply(AppServerCodexAdapter adapter, String threadId) throws Exception {
        var completed = new CompletableFuture<String>();
        var text = new StringBuffer();
        String turnId = adapter.startTurn(threadId,
                new CodexTurnInput("Reply with exactly HARNESS_OK. Do not use tools or inspect files.", null, null),
                new CodexEventListener() {
                    public void onEvent(CodexEvent event) {
                        if (event.getType() == TurnEventType.AGENT_MESSAGE_DELTA) text.append(event.getContent());
                    }
                    public void onApproval(CodexApproval approval) { completed.completeExceptionally(new AssertionError("Unexpected approval")); }
                    public void onCompleted(String id, String status, String reason) {
                        if (!"completed".equalsIgnoreCase(status)) completed.completeExceptionally(new AssertionError(CodexDiagnostics.redact(reason)));
                        else completed.complete(text.toString());
                    }
                });
        assertNotNull(turnId);
        try { assertTrue(completed.get(90, TimeUnit.SECONDS).contains("HARNESS_OK"), "No assistant reply received"); }
        finally { if (!completed.isDone()) adapter.interruptTurn(threadId, turnId); }
    }

    @Test
    @EnabledIfSystemProperty(named = "codex.resume.thread", matches = ".+")
    void resumesExistingStoredThreadWithoutSendingModelRequest() {
        AgentProperties properties = new AgentProperties();
        properties.setCodexRequestTimeoutSeconds(20);
        try (AppServerCodexAdapter adapter = new AppServerCodexAdapter(properties, new ObjectMapper())) {
            adapter.resumeThread(System.getProperty("codex.resume.thread"),
                    new CodexThreadOptions("resume-smoke-project",
                            Path.of(System.getProperty("codex.resume.workspace")), null));
        }
    }

    @Test
    void createsThreadThroughAgentAdapter(@TempDir Path workspace) {
        AgentProperties properties = new AgentProperties();
        properties.setCodexRequestTimeoutSeconds(45);
        AppServerCodexAdapter adapter = new AppServerCodexAdapter(properties, new ObjectMapper());
        List<ProcessHandle> ownedProcesses = new ArrayList<>();
        try {
            String threadId = adapter.startThread(new CodexThreadOptions("smoke-project", workspace, null));
            assertNotNull(threadId);
            assertTrue(!threadId.trim().isEmpty());
            Process started = (Process) ReflectionTestUtils.getField(adapter, "process");
            assertNotNull(started);
            ownedProcesses.addAll(started.descendants().toList());
            ownedProcesses.add(started.toHandle());
            adapter.close();
            List<Long> alive = ownedProcesses.stream().filter(ProcessHandle::isAlive).map(ProcessHandle::pid).toList();
            assertTrue(alive.isEmpty(), "Adapter close left its own processes running: " + alive);
        } finally {
            Process started = (Process) ReflectionTestUtils.getField(adapter, "process");
            if (started != null && started.isAlive()) {
                ownedProcesses.addAll(started.descendants().toList());
                ownedProcesses.add(started.toHandle());
            }
            adapter.close();
            // Cleanup is limited to handles captured from this test's own process tree, even when the assertion fails.
            for (ProcessHandle handle : ownedProcesses) {
                if (handle.isAlive()) {
                    handle.destroyForcibly();
                    try {
                        handle.onExit().get(5, TimeUnit.SECONDS);
                    } catch (Exception ignored) {
                        // The lifecycle assertion above reports the failure; JUnit still checks workspace cleanup.
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void initializesInstalledCodexUsingAgentCommand(boolean strictIsolation) throws Exception {
        Process process = new ProcessBuilder(CodexProcessCommand.appServer("codex", strictIsolation, "elevated"))
                .redirectErrorStream(true).start();
        try {
            OutputStreamWriter input = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
            input.write("{\"id\":1,\"method\":\"initialize\",\"params\":{\"clientInfo\":{\"name\":\"harness-smoke\",\"version\":\"1.0\"}}}\n");
            input.flush();
            CompletableFuture<String> response = CompletableFuture.supplyAsync(() -> {
                StringBuilder diagnostics = new StringBuilder();
                try {
                    BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = output.readLine()) != null) {
                        if (line.startsWith("{")) {
                            JsonNode message = new ObjectMapper().readTree(line);
                            if (message.path("id").asInt(-1) == 1) return line;
                        }
                        diagnostics.append(CodexDiagnostics.redact(line)).append('\n');
                    }
                    throw new AssertionError("App Server exited before initialize: " + diagnostics);
                } catch (java.io.IOException exception) {
                    throw new RuntimeException(exception);
                }
            });
            JsonNode initialized = new ObjectMapper().readTree(response.get(15, TimeUnit.SECONDS));
            assertNotNull(initialized);
            assertTrue(initialized.has("result"), CodexDiagnostics.redact(initialized.toString()));
        } finally {
            process.getOutputStream().close();
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
        }
    }
}
