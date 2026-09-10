package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "codex.smoke", matches = "true")
class CodexAppServerSmokeTest {
    @Test
    void startsIsolatedExpertWithoutInheritingPluginMcpServers(@TempDir Path workspace) {
        var properties=new AgentProperties();properties.setCodexRequestTimeoutSeconds(30);
        properties.setCodexCommand(System.getProperty("codex.smoke.command",properties.getCodexCommand()));
        try(var adapter=new AppServerCodexAdapter(properties,new ObjectMapper())) {
            var options=new CodexThreadOptions("empty-expert-mcp-probe",workspace,null)
                    .withExpertRuntime(List.of(),List.of());
            String thread=adapter.startThread(options);
            assertNotNull(thread);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "codex.turn.smoke", matches = "true")
    void repliesAfterSwitchingManagedProviderToLocalAndBack(@TempDir Path workspace,@TempDir Path data) throws Exception {
        ObjectMapper json=new ObjectMapper();
        var provider=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        provider.createContext("/responses",exchange->{
            exchange.getRequestBody().readAllBytes();
            ObjectNode message=json.createObjectNode().put("id","msg-probe").put("type","message")
                    .put("role","assistant").put("status","completed");
            message.putArray("content").addObject().put("type","output_text").put("text","HARNESS_OK").putArray("annotations");
            ObjectNode added=json.createObjectNode().put("type","response.output_item.added").put("output_index",0);
            added.set("item",message);
            ObjectNode delta=json.createObjectNode().put("type","response.output_text.delta").put("item_id","msg-probe")
                    .put("output_index",0).put("content_index",0).put("delta","HARNESS_OK");
            ObjectNode done=json.createObjectNode().put("type","response.output_item.done").put("output_index",0);done.set("item",message);
            ObjectNode completed=json.createObjectNode().put("type","response.completed");
            var response=completed.putObject("response").put("id","resp-probe").put("status","completed");
            response.putArray("output").add(message);
            response.putObject("usage").put("input_tokens",1).put("output_tokens",1).put("total_tokens",2);
            StringBuilder stream=new StringBuilder();
            for(var event:List.of(added,delta,done,completed))
                stream.append("event: ").append(event.path("type").asText()).append("\ndata: ").append(event).append("\n\n");
            byte[] bytes=stream.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        provider.start();
        try {
            var managed=new com.myharness.agent.entity.dto.ModelRuntimeDTO();managed.setSchemaVersion(2);
            managed.setRuntimeMode("MANAGED_PROVIDER");managed.setRuntimeKey("b".repeat(64));managed.setConfigurationVersionId(1L);
            managed.setBaseUrl("http://127.0.0.1:"+provider.getAddress().getPort());managed.setModelId("harness-provider-probe");
            managed.setProviderName("Harness test provider");managed.setApiKey("test-only-key");
            var local=new com.myharness.agent.entity.dto.ModelRuntimeDTO();local.setSchemaVersion(2);
            local.setRuntimeMode("LOCAL_CODEX");local.setRuntimeKey("a".repeat(64));
            var properties=new AgentProperties();properties.setDataDir(data);properties.setCodexRequestTimeoutSeconds(30);
            String thread;
            try(var adapter=new AppServerCodexAdapter(properties,json)) {
                thread=adapter.startThread(new CodexThreadOptions("switch-probe",workspace,managed));
                assertReply(adapter,thread);
            }
            try(var adapter=new AppServerCodexAdapter(properties,json)) {
                adapter.resumeThread(thread,new CodexThreadOptions("switch-probe",workspace,local));
                assertReply(adapter,thread,new CodexTurnInput("Reply exactly HARNESS_OK without tools.")
                        .withExpert("Answer directly.",List.of()));
            }
            try(var adapter=new AppServerCodexAdapter(properties,json)) {
                adapter.resumeThread(thread,new CodexThreadOptions("switch-probe",workspace,managed));
                assertReply(adapter,thread);
            }
        } finally {provider.stop(0);}
    }
    @Test
    void startsExplicitLocalCodexTarget(@TempDir Path workspace) {
        var runtime=new com.myharness.agent.entity.dto.ModelRuntimeDTO();
        runtime.setSchemaVersion(2);runtime.setRuntimeMode("LOCAL_CODEX");runtime.setRuntimeKey("a".repeat(64));
        var properties=new AgentProperties();properties.setCodexRequestTimeoutSeconds(30);
        try(var adapter=new AppServerCodexAdapter(properties,new ObjectMapper())) {
            assertNotNull(adapter.startThread(new CodexThreadOptions("local-target-probe",workspace,runtime)));
        }
    }
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
            var manager = new AgentSessionManager(adapter,registry,bus,properties, new com.myharness.agent.attachment.ConversationAttachmentService(properties,null,new ObjectMapper(),new com.myharness.agent.workspace.WorkspaceFileService(registry,properties,null,new ObjectMapper())), org.mockito.Mockito.mock(ExpertSkillPreparation.class));
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

    @Test
    @EnabledIfSystemProperty(named = "codex.turn.smoke", matches = "true")
    void repliesWhenExpertSkillsAreAvailableWithoutBeingForced(@TempDir Path workspace) throws Exception {
        Path skill=workspace.resolve(".harness/expert-runtimes/1/"+"a".repeat(64)+"/skills/harness-expert-1-1/SKILL.md");
        java.nio.file.Files.createDirectories(skill.getParent());
        java.nio.file.Files.writeString(skill,"---\nname: available-probe\ndescription: Optional expertise for Java code review.\n---\nReview Java code when requested.\n");
        var available=List.of(new CodexSkillInput("available-probe",skill.toString()));
        AgentProperties properties=new AgentProperties();properties.setCodexRequestTimeoutSeconds(45);
        try(var adapter=new AppServerCodexAdapter(properties,new ObjectMapper())) {
            String threadId=adapter.startThread(new CodexThreadOptions("expert-turn-smoke",workspace,null).withExpertSkills(available));
            assertReply(adapter,threadId,new CodexTurnInput(
                    "Reply with exactly HARNESS_OK. Do not use tools or inspect files.",null,null)
                    .withExpert("Answer the user's request directly. Skills are optional capabilities.",available));
        }
    }

    @Test
    void startsThreadWithReachableLiteralHttpHeaderMcpConfiguration(@TempDir Path workspace) throws Exception {
        var receivedHeader=new CompletableFuture<String>();
        var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/mcp",exchange -> {
            receivedHeader.complete(exchange.getRequestHeaders().getFirst("X-Mcp-Key"));
            JsonNode request=new ObjectMapper().readTree(exchange.getRequestBody());
            if(!request.has("id")) {exchange.sendResponseHeaders(202,-1);exchange.close();return;}
            ObjectNode response=new ObjectMapper().createObjectNode();response.put("jsonrpc","2.0");response.set("id",request.get("id"));
            if("initialize".equals(request.path("method").asText())) {
                var result=response.putObject("result");result.put("protocolVersion","2025-06-18").putObject("capabilities");
                result.putObject("serverInfo").put("name","header-probe").put("version","1.0");
            } else response.putObject("result").putArray("tools");
            byte[] body=new ObjectMapper().writeValueAsBytes(response);exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();
        try {
            var mcp=new com.myharness.agent.entity.dto.McpRuntimeDTO();
            mcp.setServerCode("header-probe");mcp.setTransportType("STREAMABLE_HTTP");
            mcp.setUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/mcp");
            mcp.setHttpHeaders(java.util.Map.of("X-Mcp-Key","probe-secret"));mcp.setRequired(true);
            AgentProperties properties=new AgentProperties();properties.setCodexRequestTimeoutSeconds(15);
            try(var adapter=new AppServerCodexAdapter(properties,new ObjectMapper())) {
                String threadId=adapter.startThread(new CodexThreadOptions("mcp-header-start-smoke",workspace,null)
                        .withExpertRuntime(List.of(),List.of(mcp)));
                assertNotNull(threadId);assertEquals("probe-secret",receivedHeader.get(5,TimeUnit.SECONDS));
            }
        } finally {server.stop(0);}
    }

    private void assertReply(AppServerCodexAdapter adapter, String threadId) throws Exception {
        assertReply(adapter,threadId,new CodexTurnInput(
                "Reply with exactly HARNESS_OK. Do not use tools or inspect files.",null,null));
    }

    private void assertReply(AppServerCodexAdapter adapter, String threadId, CodexTurnInput input) throws Exception {
        var completed = new CompletableFuture<String>();
        var text = new StringBuffer();
        String turnId = adapter.startTurn(threadId,input,
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
