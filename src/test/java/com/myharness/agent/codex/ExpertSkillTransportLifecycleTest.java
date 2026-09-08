package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.entity.dto.ModelRuntimeDTO;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ExpertSkillTransportLifecycleTest {
    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void registersSkillsOnTheFinalTransportBeforeStartingOrResuming(boolean resume, boolean compatibility,
                                                                  @TempDir Path root) throws Exception {
        Path workspace = Files.createDirectory(root.resolve("project"));
        Path data = Files.createDirectory(root.resolve("data"));
        Path skill = Files.createDirectories(workspace.resolve(".harness/expert-runtimes/1/probe")).resolve("SKILL.md");
        Files.writeString(skill, "---\nname: hello-skill\ndescription: Test fixture.\n---\nReply hello.\n");
        var skills = List.of(new CodexSkillInput("hello-skill", skill.toRealPath().toString()));
        String skillRoot = skill.getParent().getParent().toRealPath().toString();
        var properties = new AgentProperties();
        properties.setDataDir(data);
        properties.setResponsesHistoryCompatibility(compatibility);
        var runtime = new ModelRuntimeDTO();
        runtime.setSchemaVersion(2);
        runtime.setRuntimeMode("LOCAL_CODEX");
        runtime.setRuntimeKey("d".repeat(64));
        var options = new CodexThreadOptions("project", workspace, runtime).withExpertSkills(skills);
        String threadId = UUID.randomUUID().toString();
        var json = new ObjectMapper();
        var methods = new ArrayList<String>();
        try (var adapter = new AppServerCodexAdapter(properties, json) {
            private boolean registered;
            private Object registeredTransport;

            @Override JsonNode request(String method, JsonNode params) {
                methods.add(method);
                var result = json.createObjectNode();
                switch (method) {
                    case "config/read" -> result.putObject("config").put("model_provider", "openai").put("model", "local-probe");
                    case "model/list" -> result.putArray("data").addObject().put("model", "local-probe").put("isDefault", true);
                    case "account/read" -> result.putNull("account");
                    case "mcpServerStatus/list" -> result.putArray("data");
                    case "skills/extraRoots/set" -> {
                        assertEquals(skillRoot, params.path("extraRoots").get(0).asText());
                        registered = true;
                        registeredTransport = ReflectionTestUtils.getField(this, "historyStartupBase");
                    }
                    case "skills/list" -> {
                        var available = result.putArray("data").addObject().putArray("skills");
                        // Runtime roots belong to the process they were registered on, not to the adapter.
                        if (registered && Objects.equals(registeredTransport, ReflectionTestUtils.getField(this, "historyStartupBase")))
                            available.addObject().put("name", "hello-skill").put("path", skills.getFirst().path()).put("enabled", true);
                    }
                    case "thread/read", "thread/start", "thread/resume" -> {
                        result.putObject("thread").put("id", threadId).put("cwd", workspace.toString())
                                .putObject("status").put("type", "idle");
                        result.put("model", "local-probe").put("modelProvider", "openai");
                        result.putObject("activePermissionProfile").put("id", params.path("permissions").asText());
                    }
                    case "turn/start" -> {
                        assertTrue(params.path("collaborationMode").path("settings").path("developer_instructions").asText().contains(skills.getFirst().path()));
                        result.putObject("turn").put("id", "turn-probe");
                    }
                    default -> throw new AssertionError("Unexpected RPC: " + method);
                }
                return result;
            }
        }) {
            if (resume) adapter.resumeThread(threadId, options);
            else assertEquals(threadId, adapter.startThread(options));
            assertEquals("turn-probe", adapter.startTurn(threadId,
                    new CodexTurnInput("hello").withExpert("", skills), mock(CodexEventListener.class)));
            assertTrue(methods.indexOf("skills/extraRoots/set") < methods.indexOf(resume ? "thread/resume" : "thread/start"));
            assertEquals(1, methods.stream().filter("skills/extraRoots/set"::equals).count());
            assertEquals(resume ? 0 : 1, methods.stream().filter("thread/start"::equals).count());
        }
    }
}
