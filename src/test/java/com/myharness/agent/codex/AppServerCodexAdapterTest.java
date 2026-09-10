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
    @Test void stillRejectsUnauthorizedMcpWhenRuntimeIgnoresPluginDisable(@TempDir Path workspace) {
        var mapper=new ObjectMapper();
        for(boolean resume:List.of(false,true)) {
            var adapter=new AppServerCodexAdapter(new AgentProperties(),mapper) {
                @Override JsonNode request(String method,JsonNode input) {
                    ObjectNode result=mapper.createObjectNode();
                    if("mcpServerStatus/list".equals(method)) {
                        var data=result.putArray("data");result.putNull("nextCursor");
                        if(input.path("threadId").isTextual())
                            data.addObject().put("name","openai-api-key-local-confirmation").put("runtimeStatus","connected");
                    } else if("thread/read".equals(method)) {
                        result.putObject("thread").put("id","plugin-thread").put("cwd",workspace.toString());
                    } else if("thread/start".equals(method) || "thread/resume".equals(method)) {
                        result.putObject("activePermissionProfile").put("id",input.path("permissions").asText());
                        result.putObject("thread").put("id","plugin-thread").put("cwd",workspace.toString());
                    } else if(!"skills/extraRoots/set".equals(method)) throw new AssertionError("Unexpected RPC: "+method);
                    return result;
                }
            };
            var options=new CodexThreadOptions("project",workspace,null).withExpertRuntime(List.of(),List.of());
            var failure=assertThrows(CodexException.class,()-> {
                if(resume) adapter.resumeThread("plugin-thread",options);else adapter.startThread(options);
            });
            assertEquals("检测到未由当前 Expert 授权的 MCP Server：openai-api-key-local-confirmation",failure.getMessage());
            assertThrows(CodexException.class,()->adapter.startTurn("plugin-thread",new CodexTurnInput("hello"),
                    org.mockito.Mockito.mock(CodexEventListener.class)));
        }
    }

    @Test void doesNotActivatePluginMcpMissingFromTheInitialInventory(@TempDir Path workspace) {
        for(boolean resume:List.of(false,true)) {
            var mapper=new ObjectMapper();
            var adapter=new AppServerCodexAdapter(new AgentProperties(),mapper) {
                private boolean pluginsEnabled=true;
                @Override JsonNode request(String method,JsonNode input) {
                    ObjectNode result=mapper.createObjectNode();
                    if("mcpServerStatus/list".equals(method)) {
                        var data=result.putArray("data");result.putNull("nextCursor");
                        if(input.path("threadId").isTextual() && pluginsEnabled)
                            data.addObject().put("name","openai-api-key-local-confirmation").put("runtimeStatus","connected");
                    } else if("thread/read".equals(method)) {
                        result.putObject("thread").put("id","plugin-thread").put("cwd",workspace.toString());
                    } else if("thread/start".equals(method) || "thread/resume".equals(method)) {
                        // Plugin discovery occurs for the thread, independently of the initial MCP inventory.
                        pluginsEnabled=input.path("config").path("features").path("plugins").asBoolean(true);
                        result.putObject("activePermissionProfile").put("id",input.path("permissions").asText());
                        result.putObject("thread").put("id","plugin-thread").put("cwd",workspace.toString());
                    } else if(!"skills/extraRoots/set".equals(method)) throw new AssertionError("Unexpected RPC: "+method);
                    return result;
                }
            };
            var options=new CodexThreadOptions("project",workspace,null).withExpertRuntime(List.of(),List.of());
            if(resume) adapter.resumeThread("plugin-thread",options);
            else assertEquals("plugin-thread",adapter.startThread(options));
        }
    }

    @Test void rejectsMissingOrMismatchedEffectiveTargetDespiteMatchingThreadMetadata(@TempDir Path workspace) {
        var mapper=new ObjectMapper();
        var runtime=new com.myharness.agent.entity.dto.ModelRuntimeDTO();runtime.setSchemaVersion(2);
        runtime.setRuntimeMode("MANAGED_PROVIDER");runtime.setRuntimeKey("b".repeat(64));runtime.setModelId("test-model");
        runtime.setBaseUrl("https://models.example/v1");runtime.setApiKey("test-only-key");
        for(boolean resume:List.of(false,true)) for(String field:List.of("model","modelProvider")) for(boolean missing:List.of(false,true)) {
            var adapter=new AppServerCodexAdapter(new AgentProperties(),mapper) {
                @Override JsonNode request(String method,JsonNode input) {
                    ObjectNode result=mapper.createObjectNode();
                    result.putObject("thread").put("id","original-thread").put("cwd",workspace.toString())
                            .put("model","test-model").put("modelProvider","harness_managed");
                    if("thread/read".equals(method)) return result;
                    assertTrue("thread/start".equals(method) || "thread/resume".equals(method));
                    result.putObject("activePermissionProfile").put("id",input.path("permissions").asText());
                    result.put("model","test-model").put("modelProvider","harness_managed");
                    if(missing) result.remove(field);else result.put(field,"unexpected");
                    return result;
                }
            };
            var options=new CodexThreadOptions("project",workspace,runtime);
            assertThrows(CodexException.class,()->{if(resume) adapter.resumeThread("original-thread",options);else adapter.startThread(options);});
            assertThrows(CodexException.class,()->adapter.startTurn("original-thread",new CodexTurnInput("hello"),
                    org.mockito.Mockito.mock(CodexEventListener.class)));
        }
    }
    @Test void resolvesAndPinsLocalCodexTargetWhenResumingAnExistingThread(@TempDir Path workspace) {
        var mapper=new ObjectMapper();var methods=new ArrayList<String>();var requests=new ArrayList<JsonNode>();
        var adapter=new AppServerCodexAdapter(new AgentProperties(),mapper) {
            @Override JsonNode request(String method,JsonNode input) {
                methods.add(method);requests.add(input.deepCopy());ObjectNode result=mapper.createObjectNode();
                if("thread/read".equals(method)) {
                    result.putObject("thread").put("id","thread-local").put("cwd",workspace.toString()).putObject("status").put("type","notLoaded");
                } else if("config/read".equals(method)) {
                    result.putObject("config").put("model_provider","openai").put("model","gpt-local");
                } else if("model/list".equals(method)) {
                    result.putArray("data").addObject().put("model","gpt-local").put("isDefault",true);
                } else if("thread/resume".equals(method)) {
                    result.putObject("activePermissionProfile").put("id",input.path("permissions").asText());
                    result.put("model","gpt-local").put("modelProvider","openai");
                    result.putObject("thread").put("id","thread-local").put("cwd",workspace.toString())
                            .put("model","deepseek-v4-flash").put("modelProvider","harness_managed").putObject("status").put("type","idle");
                } else if("skills/list".equals(method)) {
                    result.putArray("data");
                } else if("turn/start".equals(method)) {
                    assertEquals("gpt-local",input.path("collaborationMode").path("settings").path("model").asText());
                    result.putObject("turn").put("id","local-turn");
                } else throw new AssertionError("Unexpected RPC: "+method);
                return result;
            }
        };
        var runtime=new com.myharness.agent.entity.dto.ModelRuntimeDTO();runtime.setSchemaVersion(2);runtime.setRuntimeMode("LOCAL_CODEX");runtime.setRuntimeKey("a".repeat(64));

        adapter.resumeThread("thread-local",new CodexThreadOptions("project",workspace,runtime));

        assertEquals(List.of("thread/read","config/read","model/list","thread/resume"),methods);
        JsonNode resume=requests.getLast();assertEquals("gpt-local",resume.path("model").asText());assertEquals("openai",resume.path("modelProvider").asText());
        assertFalse(resume.path("config").has("model_providers"));
        assertEquals("local-turn",adapter.startTurn("thread-local",
                new CodexTurnInput("hello").withExpert("Reply directly",List.of()),
                org.mockito.Mockito.mock(CodexEventListener.class)));
    }
    @Test void rejectsRemovedHttpEnvironmentMappingFields() {
        var mapper=new ObjectMapper();
        assertThrows(Exception.class,() -> mapper.readValue("{\"serverCode\":\"remote\",\"envHttpHeaders\":{\"X-Key\":\"MCP_KEY\"}}",
                com.myharness.agent.entity.dto.McpRuntimeDTO.class));
        assertThrows(Exception.class,() -> mapper.readValue("{\"serverCode\":\"remote\",\"bearerTokenEnvVar\":\"MCP_TOKEN\"}",
                com.myharness.agent.entity.dto.McpRuntimeDTO.class));
    }
    @Test void mapsPinnedMcpRuntimeIntoThreadConfiguration(@TempDir Path workspace) {
        var stdio=new com.myharness.agent.entity.dto.McpRuntimeDTO();stdio.setServerCode("github");stdio.setTransportType("STDIO");
        stdio.setCommand("npx");stdio.setArgs(List.of("-y","server"));stdio.setEnvVars(List.of("GITHUB_TOKEN"));
        stdio.setCwdMode("WORKSPACE");stdio.setStartupTimeoutSeconds(12);stdio.setToolTimeoutSeconds(90);stdio.setRequired(true);
        stdio.setEnabledTools(List.of("search"));stdio.setDisabledTools(List.of("delete"));
        var http=new com.myharness.agent.entity.dto.McpRuntimeDTO();http.setServerCode("remote");http.setTransportType("STREAMABLE_HTTP");
        http.setUrl("https://mcp.example.com/mcp");http.setHttpHeaders(java.util.Map.of("X-Mcp-Key","literal-secret"));
        var options=new CodexThreadOptions("project",workspace,null).withExpertRuntime(List.of(),List.of(stdio,http));
        ObjectNode params=new ObjectMapper().createObjectNode();params.putObject("config").put("existing",true);

        new AppServerCodexAdapter(new AgentProperties(),new ObjectMapper()).configureMcpServers(params,options);

        assertEquals("user",params.path("config").path("approvals_reviewer").asText());
        assertFalse(params.path("config").path("features").path("plugins").asBoolean(true));
        JsonNode github=params.path("config").path("mcp_servers").path("github");
        assertEquals("npx",github.path("command").asText());assertEquals(workspace.toString(),github.path("cwd").asText());
        assertEquals("approve",github.path("default_tools_approval_mode").asText());
        assertEquals("GITHUB_TOKEN",github.path("env_vars").get(0).asText());assertEquals("search",github.path("enabled_tools").get(0).asText());
        JsonNode remote=params.path("config").path("mcp_servers").path("remote");
        assertEquals("approve",remote.path("default_tools_approval_mode").asText());
        assertEquals("literal-secret",remote.path("http_headers").path("X-Mcp-Key").asText());
        assertFalse(remote.has("bearer_token_env_var"));assertFalse(remote.has("env_http_headers"));
        assertTrue(params.path("config").path("existing").asBoolean());
    }
    @Test void explicitlyDisablesInheritedMcpServersForManagedExpert(@TempDir Path workspace) {
        var options=new CodexThreadOptions("project",workspace,null).withExpertRuntime(List.of(),List.of());
        ObjectNode params=new ObjectMapper().createObjectNode();
        var adapter=new AppServerCodexAdapter(new AgentProperties(),new ObjectMapper()) {
            @Override JsonNode request(String method,JsonNode input) {
                assertEquals("mcpServerStatus/list",method);
                ObjectNode result=new ObjectMapper().createObjectNode();result.putNull("nextCursor");
                result.putArray("data").addObject().put("name","user-global").put("runtimeStatus","connected");return result;
            }
        };
        adapter.configureMcpServers(params,options);

        adapter.disableInheritedMcpServers(params,options);

        JsonNode disabled=params.path("config").path("mcp_servers").path("user-global");
        assertFalse(disabled.path("enabled").asBoolean(true));
        assertEquals("harness-disabled-mcp",disabled.path("command").asText(),
                "a disabled session override still needs a parseable transport discriminator");
        assertFalse(disabled.has("args"));
        assertFalse(disabled.has("env"));
        assertFalse(disabled.has("http_headers"));
    }
    @Test void acceptsNullRuntimeStatusForDisabledInheritedMcp(@TempDir Path workspace) {
        var mapper=new ObjectMapper();
        int[] statusRequests={0};
        var adapter=new AppServerCodexAdapter(new AgentProperties(),mapper) {
            @Override JsonNode request(String method,JsonNode input) {
                ObjectNode result=mapper.createObjectNode();
                if("mcpServerStatus/list".equals(method)) {
                    ObjectNode status=result.putArray("data").addObject().put("name","user-global");
                    if(++statusRequests[0]>1) status.putNull("runtimeStatus");
                    else status.put("runtimeStatus","connected");
                    result.putNull("nextCursor");
                    return result;
                }
                if("skills/extraRoots/set".equals(method)) return result;
                if("thread/start".equals(method)) {
                    result.putObject("activePermissionProfile").put("id",input.path("permissions").asText());
                    result.putObject("thread").put("id","managed-thread");
                    return result;
                }
                throw new AssertionError("Unexpected RPC: "+method);
            }
        };
        var options=new CodexThreadOptions("project",workspace,null).withExpertRuntime(List.of(),List.of());

        assertEquals("managed-thread",adapter.startThread(options));
    }
    @Test void doesNotSerializeCodexAppsPseudoTransportAsAStandardMcpOverride(@TempDir Path workspace) {
        var options=new CodexThreadOptions("project",workspace,null).withExpertRuntime(List.of(),List.of());
        ObjectNode params=new ObjectMapper().createObjectNode();
        var adapter=new AppServerCodexAdapter(new AgentProperties(),new ObjectMapper()) {
            @Override JsonNode request(String method,JsonNode input) {
                assertEquals("mcpServerStatus/list",method);
                ObjectNode result=new ObjectMapper().createObjectNode();result.putNull("nextCursor");
                result.putArray("data").addObject().put("name","codex_apps").put("runtimeStatus","connected");return result;
            }
        };
        adapter.configureMcpServers(params,options);

        adapter.disableInheritedMcpServers(params,options);

        assertFalse(params.path("config").path("features").path("apps").asBoolean(true),
                "managed expert threads must disable the reserved Codex Apps MCP through its feature flag");
        assertFalse(params.path("config").path("mcp_servers").has("codex_apps"),
                "codex_apps is a special Apps transport and is invalid inside a standard mcp_servers session override");
    }
    @Test void refreshesDiscoveryAndLeavesBoundSkillAvailableWithoutChangingUserInput(@TempDir Path workspace) throws Exception {
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
        assertTrue(instructions.contains(skill.toRealPath().toString()));
        assertTrue(instructions.contains("可选能力"));
        assertTrue(instructions.contains("不要替换为用户目录或项目 .agents/skills 下的同名路径"));
        assertEquals("Hello",request.path("input").get(0).path("text").asText());
        assertEquals(1,request.path("input").size());
        assertFalse(request.toString().contains("$review"));
        assertFalse(request.toString().contains("\"type\":\"skill\""));
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
        assertEquals("first",first.path("input").get(0).path("text").asText());
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
    void restoresStoredThreadBeforeStartingTurnWithRestrictedWorkspaceAndInteractiveApprovals(@TempDir Path workspace) {
        var adapter = new StoredThreadAdapter(workspace);
        adapter.resumeThread("original-thread", new CodexThreadOptions("project", workspace, null));
        adapter.startTurn("original-thread", new CodexTurnInput("hello again", null, null),
                org.mockito.Mockito.mock(CodexEventListener.class));

        assertEquals(List.of("thread/read", "thread/resume", "turn/start"), adapter.methods);
        JsonNode read = adapter.params.get(0);
        assertFalse(read.path("includeTurns").asBoolean());
        JsonNode resume = adapter.params.get(1);
        assertEquals("original-thread", resume.path("threadId").asText());
        assertEquals("on-request", resume.path("approvalPolicy").asText());
        assertFalse(resume.has("sandbox"));
        String profile=resume.path("permissions").asText();
        assertEquals(profile,resume.path("config").path("default_permissions").asText());
        var policy=resume.path("config").path("permissions").path(profile);
        assertEquals(":workspace",policy.path("extends").asText());
        assertEquals("write",policy.path("filesystem").path(":workspace_roots").path(".").asText());
        assertEquals("read",policy.path("filesystem").path(":workspace_roots").path(".git").asText());
        assertEquals("read",policy.path("filesystem").path(":workspace_roots").path(".codex").asText());
        assertFalse(policy.path("network").path("enabled").asBoolean());
        JsonNode turn = adapter.params.get(2);
        assertEquals("original-thread", turn.path("threadId").asText());
        assertEquals(workspace.toString(), turn.path("cwd").asText());
        assertEquals("on-request", turn.path("approvalPolicy").asText());
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

    @Test
    void surfacesMcpToolCallApprovalAndReturnsTheSelectedAnswer(@TempDir Path workspace) {
        var mapper=new ObjectMapper();var adapter=new StoredThreadAdapter(workspace);
        var listener=org.mockito.Mockito.mock(CodexEventListener.class);
        adapter.resumeThread("original-thread",new CodexThreadOptions("project",workspace,null));
        adapter.startTurn("original-thread",new CodexTurnInput("query sales",null,null),listener);
        ObjectNode request=mapper.createObjectNode().put("id","mcp-approval").put("method","item/tool/requestUserInput");
        ObjectNode params=request.putObject("params").put("threadId","original-thread").put("turnId","new-turn")
                .put("itemId","tool-1").put("isBlocking",true);
        var question=params.putArray("questions").addObject().put("id","approval").put("header","Approval")
                .put("question","Allow this MCP tool call?");
        question.putArray("options").addObject().put("label","Accept").put("description","Run once");
        question.withArray("options").addObject().put("label","Decline").put("description","Do not run");
        question.withArray("options").addObject().put("label","Cancel").put("description","Stop");

        adapter.handleMessage(request);

        var approval=org.mockito.ArgumentCaptor.forClass(CodexApproval.class);
        org.mockito.Mockito.verify(listener).onApproval(approval.capture());
        assertEquals("MCP_TOOL_CALL",approval.getValue().getType().name());
        adapter.resolveApproval("mcp-approval",com.myharness.agent.entity.enums.ApprovalDecision.ACCEPT);
        JsonNode response=adapter.writes.getLast();
        assertEquals("Accept",response.path("result").path("answers").path("approval").path("answers").get(0).asText());
    }

    private static final class StoredThreadAdapter extends AppServerCodexAdapter {
        private final ObjectMapper mapper = new ObjectMapper();
        private final Path workspace;
        private final List<String> methods = new ArrayList<>();
        private final List<JsonNode> params = new ArrayList<>();
        private final List<JsonNode> writes = new ArrayList<>();
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

        @Override void write(JsonNode message) { writes.add(message.deepCopy()); }
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
