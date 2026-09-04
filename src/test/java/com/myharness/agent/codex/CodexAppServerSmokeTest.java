package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.agent.config.AgentProperties;
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
        properties.setCodexRequestTimeoutSeconds(15);
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
