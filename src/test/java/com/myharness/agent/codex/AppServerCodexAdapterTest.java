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
    @Test void refreshesDiscoveryAndInvokesBoundSkillByNativeNameAndPath(@TempDir Path workspace) throws Exception {
        Path skill=workspace.resolve(".harness/expert-runtimes/1/runtime/skills/harness-expert-1-1/SKILL.md");
        Files.createDirectories(skill.getParent());
        Files.writeString(skill,"---\nname: hello-skill\ndescription: Use for greetings.\n---\nWhen greeted, reply HELLO_FROM_HARNESS_SKILL.");
        var adapter=new StoredThreadAdapter(workspace);
        adapter.configureSkillRoots(workspace,List.of(new CodexSkillInput("platform-name",skill.toString())));
        adapter.resumeThread("original-thread",new CodexThreadOptions("project",workspace,"test-model"));
        adapter.startTurn("original-thread",new CodexTurnInput("Hello",null,null)
                .withExpert("Use the bound Skills when applicable.",List.of(new CodexSkillInput("hello-skill",skill.toString()))),
                org.mockito.Mockito.mock(CodexEventListener.class));
        var request=adapter.params.getLast();
        String instructions=request.path("collaborationMode").path("settings").path("developer_instructions").asText();
        assertTrue(instructions.contains("Use the bound Skills"));
        assertEquals("Hello\n\n$review",request.path("input").get(0).path("text").asText());
        assertEquals("skill",request.path("input").get(1).path("type").asText());
        assertEquals(skill.toRealPath().toString(),request.path("input").get(1).path("path").asText());
        assertEquals(List.of("skills/extraRoots/set","thread/read","thread/resume","skills/list","turn/start"),adapter.methods);
        assertTrue(adapter.params.get(3).path("forceReload").asBoolean());
    }
    @Test void appliesEachExpertAndExplicitlyClearsInstructionsWithoutChangingWorkspace(@TempDir Path workspace) throws Exception {
        var adapter=new StoredThreadAdapter(workspace);
        adapter.resumeThread("original-thread",new CodexThreadOptions("project",workspace,"test-model"));
        var listener=org.mockito.Mockito.mock(CodexEventListener.class);
        var skill=new CodexSkillInput("review",workspace.resolve(".harness/expert-runtimes/1/runtime/skills/harness-expert-1-2/SKILL.md").toString());
        Files.createDirectories(Path.of(skill.path()).getParent());
        Files.writeString(Path.of(skill.path()),"# Review\nReview project code.");
        adapter.configureSkillRoots(workspace,List.of(skill));
        adapter.startTurn("original-thread",new CodexTurnInput("first",null,"high").withExpert("Java expert",List.of(skill)),listener);
        adapter.startTurn("original-thread",new CodexTurnInput("second",null,null).withExpert("SQL expert",List.of(skill)),listener);
        adapter.startTurn("original-thread",new CodexTurnInput("third",null,null).withExpert(null,List.of()),listener);
        var first=adapter.params.get(4);var second=adapter.params.get(6);var cleared=adapter.params.get(8);
        assertEquals("first\n\n$review",first.path("input").get(0).path("text").asText());
        assertTrue(first.path("collaborationMode").path("settings").path("developer_instructions").asText().contains("Java expert"));
        assertFalse(second.toString().contains("Java expert"));assertTrue(second.toString().contains("SQL expert"));
        assertTrue(cleared.path("collaborationMode").path("settings").path("developer_instructions").isNull());
        assertEquals("test-model",cleared.path("collaborationMode").path("settings").path("model").asText());
        assertEquals(1,cleared.path("input").size());
        assertFalse(cleared.toString().contains("Review project code."));
        assertFalse(cleared.has("config"));assertEquals(workspace.toString(),cleared.path("cwd").asText());
    }
    @Test void refusesExpertExecutionWhenActualModelIsUnknown(@TempDir Path workspace) {
        var adapter=new StoredThreadAdapter(workspace);
        adapter.resumeThread("original-thread",new CodexThreadOptions("project",workspace,null));
        assertThrows(CodexException.class,()->adapter.startTurn("original-thread",new CodexTurnInput("hello",null,null).withExpert("expert",List.of()),org.mockito.Mockito.mock(CodexEventListener.class)));
        assertEquals(List.of("thread/read","thread/resume","skills/list"),adapter.methods);
    }
    @Test void refusesDisabledSkillBeforeStartingModelTurn(@TempDir Path workspace) throws Exception {
        Path skill=workspace.resolve(".harness/expert-runtimes/1/runtime/skills/expert/SKILL.md");
        Files.createDirectories(skill.getParent());Files.writeString(skill,"disabled");
        var adapter=new StoredThreadAdapter(workspace);adapter.disabledSkill=true;
        adapter.configureSkillRoots(workspace,List.of(new CodexSkillInput("review",skill.toString())));
        adapter.resumeThread("original-thread",new CodexThreadOptions("project",workspace,"test-model"));
        assertThrows(CodexException.class,()->adapter.startTurn("original-thread",new CodexTurnInput("hello",null,null)
                .withExpert("expert",List.of(new CodexSkillInput("review",skill.toString()))),org.mockito.Mockito.mock(CodexEventListener.class)));
        assertFalse(adapter.methods.contains("turn/start"));
    }
    @Test void refusesSkillAbsentFromNativeDiscoveryBeforeStartingModelTurn(@TempDir Path workspace) throws Exception {
        Path skill=workspace.resolve(".harness/expert-runtimes/1/runtime/skills/expert/SKILL.md");
        Files.createDirectories(skill.getParent());Files.writeString(skill,"not discovered");
        var adapter=new StoredThreadAdapter(workspace);adapter.missingSkill=true;
        adapter.configureSkillRoots(workspace,List.of(new CodexSkillInput("review",skill.toString())));
        adapter.resumeThread("original-thread",new CodexThreadOptions("project",workspace,"test-model"));
        assertThrows(CodexException.class,()->adapter.startTurn("original-thread",new CodexTurnInput("hello",null,null)
                .withExpert("expert",List.of(new CodexSkillInput("review",skill.toString()))),org.mockito.Mockito.mock(CodexEventListener.class)));
        assertFalse(adapter.methods.contains("turn/start"));
    }
    @Test void refusesExpertSkillOutsideCurrentProjectNativeDirectory(@TempDir Path root) throws Exception {
        Path workspace=Files.createDirectory(root.resolve("project"));Path skill=root.resolve("SKILL.md");Files.writeString(skill,"other project");
        var adapter=new StoredThreadAdapter(workspace);
        adapter.resumeThread("original-thread",new CodexThreadOptions("project",workspace,"test-model"));
        assertThrows(CodexException.class,()->adapter.startTurn("original-thread",new CodexTurnInput("hello",null,null)
                .withExpert("expert",List.of(new CodexSkillInput("review",skill.toString()))),org.mockito.Mockito.mock(CodexEventListener.class)));
        assertFalse(adapter.methods.contains("turn/start"));
    }
    @Test void classifiesOnlyExactMissingThreadReadResponse(@TempDir Path workspace) {
        var adapter=new StoredThreadAdapter(workspace);
        adapter.readFailure=new CodexException("read failed",new CodexException("thread not loaded: original-thread"));
        assertThrows(CodexThreadNotLoadedException.class,()->adapter.resumeThread("original-thread",new CodexThreadOptions("project",workspace,null)));
        adapter.readFailure=new CodexException("read failed",new CodexException("thread not loaded: another-thread"));
        var failure=assertThrows(CodexException.class,()->adapter.resumeThread("original-thread",new CodexThreadOptions("project",workspace,null)));
        assertFalse(failure instanceof CodexThreadNotLoadedException);
        assertEquals(List.of("thread/read","thread/read"),adapter.methods);
    }
    @Test void refusesLegacyFallbackWithoutActivatingProjectProfile(@TempDir Path workspace) {
        var adapter=new StoredThreadAdapter(workspace);adapter.ignoreProfile=true;
        assertThrows(CodexException.class,()->adapter.resumeThread("original-thread",new CodexThreadOptions("project",workspace,null)));
        assertThrows(CodexException.class,()->adapter.startTurn("original-thread",new CodexTurnInput("read another project",null,null),
                org.mockito.Mockito.mock(CodexEventListener.class)));
        assertEquals(List.of("thread/read","thread/resume"),adapter.methods);
    }
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
        assertFalse(resume.has("sandbox"));
        String profile=resume.path("permissions").asText();
        var policy=resume.path("config").path("permissions").path(profile);
        assertEquals("deny",policy.path("filesystem").path(":root").asText());
        assertEquals("read",policy.path("filesystem").path(":minimal").asText());
        assertEquals("write",policy.path("filesystem").path(workspace.toString()).path(".").asText());
        assertEquals("deny",policy.path("filesystem").path(":tmpdir").asText());
        assertFalse(policy.path("network").path("enabled").asBoolean());
        JsonNode turn = adapter.params.get(2);
        assertEquals("original-thread", turn.path("threadId").asText());
        assertEquals(workspace.toString(), turn.path("cwd").asText());
        assertEquals("never", turn.path("approvalPolicy").asText());
        assertFalse(turn.has("sandboxPolicy"));
        assertFalse(turn.has("permissions"), "Turns must inherit the verified thread policy, not reload an inline-only profile by name");
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
        private boolean ignoreProfile;
        private boolean disabledSkill;
        private boolean missingSkill;
        private CodexException readFailure;

        StoredThreadAdapter(Path workspace) {
            super(new AgentProperties(), new ObjectMapper());
            this.workspace = workspace;
        }

        @Override JsonNode request(String method, JsonNode input) {
            methods.add(method);
            params.add(input.deepCopy());
            ObjectNode result = mapper.createObjectNode();
            if ("thread/read".equals(method) || "thread/resume".equals(method)) {
                if ("thread/read".equals(method) && readFailure!=null) throw readFailure;
                if ("thread/resume".equals(method) && failResume) throw new CodexException("Resume failed");
                if ("thread/resume".equals(method) && !ignoreProfile) result.putObject("activePermissionProfile").put("id",input.path("permissions").asText());
                result.putObject("thread").put("id", "original-thread").put("cwd", workspace.toString())
                        .putObject("status").put("type", active ? "active" : "notLoaded");
            } else if ("turn/start".equals(method)) {
                if (input.hasNonNull("permissions")) throw new CodexException("failed to load configuration: default_permissions requires a `[permissions]` table");
                result.putObject("turn").put("id", "new-turn");
            } else if ("skills/extraRoots/set".equals(method)) {
                return result;
            } else if ("skills/list".equals(method)) {
                var group=result.putArray("data").addObject().put("cwd",workspace.toString());
                var found=group.putArray("skills");
                if(missingSkill) return result;
                try(var files=Files.walk(workspace)) {
                    for(var file:files.filter(p->p.getFileName().toString().equals("SKILL.md")).toList())
                        found.addObject().put("path",file.toRealPath().toString()).put("name","review").put("enabled",!disabledSkill);
                } catch(java.io.IOException failure) {throw new AssertionError(failure);}
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
