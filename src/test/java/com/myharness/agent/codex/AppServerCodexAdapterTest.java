package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myharness.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppServerCodexAdapterTest {
    @Test
    void restoresStoredThreadBeforeStartingTurnWithRestrictedWorkspace(@TempDir Path workspace) {
        var adapter = new StoredThreadAdapter(workspace);
        adapter.resumeThread("original-thread", new CodexThreadOptions("project", workspace, null));
        adapter.startTurn("original-thread", new CodexTurnInput("hello again", null, null),
                org.mockito.Mockito.mock(CodexEventListener.class));

        assertEquals(List.of("thread/read", "thread/resume", "turn/start"), adapter.methods);
        JsonNode read = adapter.params.get(0);
        assertFalse(read.path("includeTurns").asBoolean());
        JsonNode resume = adapter.params.get(1);
        assertEquals("original-thread", resume.path("threadId").asText());
        assertEquals("never", resume.path("approvalPolicy").asText());
        assertEquals("workspace-write", resume.path("sandbox").asText());
        JsonNode turn = adapter.params.get(2);
        assertEquals("original-thread", turn.path("threadId").asText());
        assertEquals(workspace.toString(), turn.path("cwd").asText());
        assertEquals("never", turn.path("approvalPolicy").asText());
        assertEquals("workspaceWrite", turn.path("sandboxPolicy").path("type").asText());
        assertFalse(turn.path("sandboxPolicy").path("networkAccess").asBoolean());
        assertEquals(1, turn.path("sandboxPolicy").path("writableRoots").size());
        assertEquals(workspace.toString(), turn.path("sandboxPolicy").path("writableRoots").get(0).asText());
    }

    @Test
    void refusesStoredThreadFromAnotherWorkspaceBeforeResuming(@TempDir Path root) throws Exception {
        Path original = Files.createDirectory(root.resolve("original"));
        Path other = Files.createDirectory(root.resolve("other"));
        var adapter = new StoredThreadAdapter(original);
        assertThrows(CodexException.class,
                () -> adapter.resumeThread("original-thread", new CodexThreadOptions("project", other, null)));
        assertEquals(List.of("thread/read"), adapter.methods);
        assertThrows(CodexException.class, () -> adapter.startTurn("original-thread",
                new CodexTurnInput("hello", null, null), org.mockito.Mockito.mock(CodexEventListener.class)));
        assertEquals(1, adapter.methods.size(), "Rejected recovery must not create a usable mapping");
    }

    @Test
    void failedResumeCanBeRetriedWithoutCreatingNewThread(@TempDir Path workspace) {
        var adapter = new StoredThreadAdapter(workspace);
        adapter.failResume = true;
        assertThrows(CodexException.class,
                () -> adapter.resumeThread("original-thread", new CodexThreadOptions("project", workspace, null)));
        assertThrows(CodexException.class, () -> adapter.startTurn("original-thread",
                new CodexTurnInput("hello", null, null), org.mockito.Mockito.mock(CodexEventListener.class)));
        adapter.failResume = false;
        adapter.resumeThread("original-thread", new CodexThreadOptions("project", workspace, null));
        assertEquals(List.of("thread/read", "thread/resume", "thread/read", "thread/resume"), adapter.methods);
    }

    @Test
    void refusesMismatchedThreadIdAndActiveStoredTurn(@TempDir Path workspace) {
        var adapter = new StoredThreadAdapter(workspace);
        assertThrows(CodexException.class,
                () -> adapter.resumeThread("another-thread", new CodexThreadOptions("project", workspace, null)));
        adapter.active = true;
        assertThrows(CodexException.class,
                () -> adapter.resumeThread("original-thread", new CodexThreadOptions("project", workspace, null)));
        assertEquals(List.of("thread/read", "thread/read"), adapter.methods);
    }

    private static final class StoredThreadAdapter extends AppServerCodexAdapter {
        private final ObjectMapper mapper = new ObjectMapper();
        private final Path workspace;
        private final List<String> methods = new ArrayList<>();
        private final List<JsonNode> params = new ArrayList<>();
        private boolean failResume;
        private boolean active;

        StoredThreadAdapter(Path workspace) {
            super(new AgentProperties(), new ObjectMapper());
            this.workspace = workspace;
        }

        @Override JsonNode request(String method, JsonNode input) {
            methods.add(method);
            params.add(input.deepCopy());
            ObjectNode result = mapper.createObjectNode();
            if ("thread/read".equals(method) || "thread/resume".equals(method)) {
                if ("thread/resume".equals(method) && failResume) throw new CodexException("Resume failed");
                result.putObject("thread").put("id", "original-thread").put("cwd", workspace.toString())
                        .putObject("status").put("type", active ? "active" : "notLoaded");
            } else if ("turn/start".equals(method)) {
                result.putObject("turn").put("id", "new-turn");
            } else {
                throw new AssertionError("Unexpected RPC: " + method);
            }
            return result;
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void reportsStartupDiagnosticsWithoutCredentials(@TempDir Path directory) throws Exception {
        Path command = directory.resolve("codex.cmd");
        Files.write(command, ("@echo off\r\nset /p request=\r\n"
                + "echo Error loading config: unknown field test_field 1>&2\r\n"
                + "echo api_key=example-secret Authorization: Bearer example-token 1>&2\r\n"
                + "exit /b 1\r\n").getBytes(StandardCharsets.UTF_8));
        AgentProperties properties = new AgentProperties();
        properties.setCodexCommand(command.toString());
        properties.setCodexRequestTimeoutSeconds(5);
        AppServerCodexAdapter adapter = new AppServerCodexAdapter(properties, new ObjectMapper());
        try {
            CodexException failure = assertThrows(CodexException.class,
                    () -> adapter.startThread(new CodexThreadOptions("project", directory, null)));
            assertTrue(failure.getMessage().contains("Error loading config: unknown field test_field"),
                    failure.getMessage());
            assertFalse(failure.getMessage().contains("example-secret"));
            assertFalse(failure.getMessage().contains("example-token"));
        } finally {
            adapter.close();
        }
    }

    @Test void carriesAgentMessagePhaseFromItemStartToTextDelta() {
        ObjectMapper mapper = new ObjectMapper();
        AppServerCodexAdapter adapter = new AppServerCodexAdapter(new AgentProperties(), mapper);
        ObjectNode started = mapper.createObjectNode();
        started.putObject("item").put("id", "message-1").put("type", "agentMessage").put("phase", "commentary");
        adapter.translateEvent("item/started", started);
        ObjectNode delta = mapper.createObjectNode().put("itemId", "message-1").put("delta", "正在查询天气");

        CodexEvent event = adapter.translateEvent("item/agentMessage/delta", delta);

        assertEquals("commentary", event.getPhase());
    }
}
