package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myharness.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppServerCodexAdapterTest {
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
