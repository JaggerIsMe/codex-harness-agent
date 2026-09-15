package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.entity.enums.ApprovalDecision;
import com.myharness.agent.entity.enums.ApprovalType;
import com.myharness.agent.entity.enums.TurnEventType;
import com.myharness.agent.security.AgentMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public class AppServerCodexAdapter implements CodexGateway {
    private static final String CODEX_APPS_MCP_SERVER="codex_apps";
    private static final String DISABLED_INHERITED_MCP_COMMAND="harness-disabled-mcp";
    private static final String INTERACTIVE_APPROVAL_POLICY="on-request";
    private java.util.Set<Path> expertSkillPaths=java.util.Set.of();
    private static final Logger LOGGER = LoggerFactory.getLogger(AppServerCodexAdapter.class);

    private final AgentProperties properties;
    private final ObjectMapper objectMapper;
    private final AtomicLong requestSequence = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();
    private final Map<String, CodexEventListener> listenersByTurn = new ConcurrentHashMap<>();
    private final Map<String, CodexEventListener> listenersByThread = new ConcurrentHashMap<>();
    private final java.util.Set<String> orchestrationThreads=ConcurrentHashMap.newKeySet();
    private final Map<String, Path> threadWorkspaces = new ConcurrentHashMap<>();
    private final java.util.Set<String> imageInputThreads=ConcurrentHashMap.newKeySet();
    private final Map<String,Thread> nativeCommands=new ConcurrentHashMap<>();
    private final java.util.Set<String> cancelledNativeTurns=ConcurrentHashMap.newKeySet();
    private boolean nativeWindows() {return properties.isStrictProjectIsolation() && properties.getWindowsPython()!=null && System.getProperty("os.name","").startsWith("Windows");}
    private final Map<String, String> threadModels = new ConcurrentHashMap<>();
    private ResponsesCompatibilityProxy historyProxy;
    private String historyStartupBase;
    private final Map<String, PendingApproval> pendingApprovals = new ConcurrentHashMap<>();
    private final Map<String, String> messagePhasesByItem = new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();
    private final Object writeLock = new Object();

    private volatile Process process;
    private volatile BufferedWriter writer;
    private volatile boolean closing;
    private volatile boolean runtimeFailed;
    private volatile com.myharness.agent.entity.dto.ModelRuntimeDTO startupModelRuntime;
    private Path localModelCatalog;
    private com.myharness.agent.usage.ManagedUsageClient usageClient;
    public AppServerCodexAdapter withUsageClient(com.myharness.agent.usage.ManagedUsageClient value){usageClient=value;return this;}
    @Override public boolean isAvailable() {return !runtimeFailed && process!=null && process.isAlive();}

    public AppServerCodexAdapter(AgentProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String startThread(CodexThreadOptions options) {
        if (options == null || options.getWorkspace() == null) {
            throw new CodexException("Workspace is required to start a Codex thread");
        }
        if (properties.isStrictProjectIsolation() && !hasText(options.getProjectId())) {
            throw new CodexException("Project ID is required in strict project isolation mode");
        }
        prepareNativeWorkspace(options.getWorkspace());
        activateModelRuntime(options.getModelRuntime());
        ObjectNode params = objectMapper.createObjectNode();
        params.put("cwd", options.getWorkspace().toString());
        params.put("approvalPolicy", INTERACTIVE_APPROVAL_POLICY);
        ExecutionConfirmation.configure(params);
        configureProjectPermissions(params, options.getWorkspace());
        ModelTarget modelTarget=configureModelTarget(params,options);
        configureHistoryProxy(params,options,modelTarget);
        configureMcpServers(params,options);
        disableInheritedMcpServers(params,options);
        if(nativeWindows()) WindowsExecutionTools.configure(params,true,supportsImageInput(options),controlledImages());
        if(nativeWindows()) WindowsExecutionTools.configureCommands(params,properties);
        if(options.isOrchestration()) OrchestrationOutcomeTool.configure(params);
        params.put("ephemeral", false);
        if(options.isIsolatedExpertRuntime()) configureSkillRoots(options.getWorkspace(),options.getExpertSkills());
        JsonNode result = request("thread/start", params);
        verifyPermissionProfile(result,options.getWorkspace());
        verifyModelTarget(result,modelTarget);
        String threadId = requiredText(result.path("thread"), "id", "thread/start response");
        verifyMcpIsolation(threadId,options);
        if(historyProxy!=null) historyProxy.bind(threadId);
        threadWorkspaces.put(threadId, options.getWorkspace());
        if(supportsImageInput(options))imageInputThreads.add(threadId);else imageInputThreads.remove(threadId);
        if(nativeWindows()) markNativeThread(threadId,options.getWorkspace(),true);
        rememberModel(threadId,result,options.getModel());
        return threadId;
    }

    @Override
    public void resumeThread(String threadId, CodexThreadOptions options) {
        requireText(threadId, "Codex thread ID");
        if (options == null || options.getWorkspace() == null) {
            throw new CodexException("Workspace is required to resume a Codex thread");
        }
        if (properties.isStrictProjectIsolation() && !hasText(options.getProjectId())) {
            throw new CodexException("Project ID is required in strict project isolation mode");
        }
        prepareNativeWorkspace(options.getWorkspace());
        activateModelRuntime(options.getModelRuntime());
        // Verify the persisted root before applying overrides: resume must not move another project's history.
        ObjectNode read = objectMapper.createObjectNode().put("threadId", threadId).put("includeTurns", false);
        JsonNode stored;
        try { stored = request("thread/read", read).path("thread"); }
        catch (CodexException failure) {
            Throwable detail = failure.getCause();
            if (detail != null && ("thread not loaded: " + threadId).equals(detail.getMessage()))
                throw new CodexThreadNotLoadedException(threadId, failure);
            throw failure;
        }
        verifyThreadBinding(stored, threadId, options.getWorkspace());
        if(nativeWindows()) markNativeThread(threadId,options.getWorkspace(),false);
        if ("active".equals(stored.path("status").path("type").asText())) {
            throw new CodexException("Cannot resume a Codex thread with an active Turn");
        }
        ObjectNode params = objectMapper.createObjectNode();
        params.put("threadId", threadId);
        params.put("cwd", options.getWorkspace().toString());
        params.put("approvalPolicy", INTERACTIVE_APPROVAL_POLICY);
        ExecutionConfirmation.configure(params);
        configureProjectPermissions(params, options.getWorkspace());
        ModelTarget modelTarget=configureModelTarget(params,options);
        configureHistoryProxy(params,options,modelTarget);
        configureMcpServers(params,options);
        disableInheritedMcpServers(params,options);
        if(nativeWindows()) WindowsExecutionTools.configure(params,false,supportsImageInput(options),controlledImages());
        // Extra skill roots are process-local. Register after transport bootstrap may restart
        // the discovery process, and before the resumed thread discovers its expert skills.
        if(options.isIsolatedExpertRuntime()) configureSkillRoots(options.getWorkspace(),options.getExpertSkills());
        if(historyProxy!=null) historyProxy.bind(threadId);
        JsonNode resumeResult = request("thread/resume", params);
        verifyPermissionProfile(resumeResult,options.getWorkspace());
        verifyModelTarget(resumeResult,modelTarget);
        JsonNode resumed = resumeResult.path("thread");
        verifyThreadBinding(resumed, threadId, options.getWorkspace());
        verifyMcpIsolation(threadId,options);
        threadWorkspaces.put(threadId, options.getWorkspace());
        if(supportsImageInput(options))imageInputThreads.add(threadId);else imageInputThreads.remove(threadId);
        rememberModel(threadId,resumeResult,options.getModel());
    }

    private boolean supportsImageInput(CodexThreadOptions options) {
        var runtime=options.getModelRuntime();
        return runtime==null||"LOCAL_CODEX".equals(runtime.getRuntimeMode())||runtime.supports("IMAGE");
    }
    private boolean controlledImages(){return historyProxy!=null&&historyProxy.supportsControlledImages();}
    private void verifyThreadBinding(JsonNode thread, String threadId, Path workspace) {
        if (!threadId.equals(requiredText(thread, "id", "Codex thread response"))) {
            throw new CodexException("Resumed Codex thread ID does not match the Conversation");
        }
        String cwd = requiredText(thread, "cwd", "Codex thread response");
        try {
            if (!Path.of(cwd).toRealPath().equals(workspace.toRealPath())) {
                throw new CodexException("Stored Codex thread workspace does not match the allowed project workspace");
            }
        } catch (IOException | java.nio.file.InvalidPathException exception) {
            throw new CodexException("Unable to verify stored Codex thread workspace", exception);
        }
    }

    private void markNativeThread(String threadId,Path workspace,boolean create) {
        try {
            Path directory=properties.getDataDir().resolve("native-windows-threads");
            Path marker=directory.resolve(java.util.UUID.nameUUIDFromBytes(threadId.getBytes(StandardCharsets.UTF_8))+".txt");
            String binding=workspace.toRealPath().toString();
            if(create) {java.nio.file.Files.createDirectories(directory);java.nio.file.Files.writeString(marker,binding);}
            else if(!java.nio.file.Files.isRegularFile(marker) || !java.nio.file.Files.readString(marker).equals(binding))
                throw new CodexException("此会话使用旧 Windows 执行工具；请在当前项目中新建会话以启用读取隔离，旧历史保持可查看");
        } catch(IOException failure) {throw new CodexException("Cannot verify native Windows thread binding",failure);}
    }

    private void prepareNativeWorkspace(Path workspace) {
        if(!nativeWindows()) return;
        try {
            if(properties.getDataDir().toRealPath().startsWith(workspace.toRealPath()))
                throw new CodexException("Agent 状态目录必须位于项目工作区之外");
        } catch(IOException failure) {throw new CodexException("Cannot verify Agent state isolation",failure);}
        if(java.nio.file.Files.exists(workspace.resolve(".codex/config.toml"),java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new CodexException("Windows 隔离项目不加载 .codex/config.toml；请由管理员将需要的配置迁入受控 Agent 配置");
        try {
            var probe=WindowsIsolatedCommand.execute(workspace,"pass",15,properties.getWindowsPython(),com.myharness.agent.workspace.AgentStorage.executionDirectory(properties.getDataDir(),workspace));
            if(probe.exitCode()!=0) throw new CodexException("Cannot initialize Windows project execution: "+probe.output());
        } catch(IOException failure) {throw new CodexException("Cannot prepare isolated execution directory",failure);}
    }

    private String profileId(Path workspace) {
        return "harness-" + java.util.UUID.nameUUIDFromBytes(
                workspace.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8)).toString().replace("-","");
    }

    private void rememberModel(String threadId, JsonNode response, String fallback) {
        String model=response.path("model").asText(fallback);
        if(hasText(model)) threadModels.put(threadId,model);
    }

    private boolean historyEnabled(CodexThreadOptions options) {
        return options.getModelRuntime()!=null && options.getModelRuntime().getSchemaVersion()>=2
            && (properties.isResponsesHistoryCompatibility() || options.getModelRuntime().getSchemaVersion()>=3);
    }
    private void configureHistoryProxy(ObjectNode params,CodexThreadOptions options,ModelTarget target) {
        if(!historyEnabled(options)) return;
        ObjectNode config=params.withObject("config");
        boolean managed="MANAGED_PROVIDER".equals(options.getModelRuntime().getRuntimeMode());
        JsonNode effective=null;boolean chatgpt=false;
        String upstream=options.getModelRuntime().getBaseUrl(),accountIdentity="";
        if(!managed) {
            effective=request("config/read",objectMapper.createObjectNode().put("cwd",options.getWorkspace().toString())).path("config");
            JsonNode provider=effective.path("model_providers").path(target.provider());
            if(!"responses".equals(provider.path("wire_api").asText("responses"))) throw new CodexException("HISTORY_INCOMPATIBLE: Local provider must use Responses");
            JsonNode account=request("account/read",objectMapper.createObjectNode().put("refreshToken",false)).path("account");
            chatgpt=("openai".equals(target.provider()) || provider.path("requires_openai_auth").asBoolean(false))
                    && account.path("type").asText().startsWith("chatgpt");
            accountIdentity=account.path("email").asText();
            if("openai".equals(target.provider())) {
                upstream=effective.path("openai_base_url").asText(null);
                if(upstream==null) upstream=chatgpt?effective.path("chatgpt_base_url").asText("https://chatgpt.com/backend-api").replaceAll("/+$","")+"/codex":"https://api.openai.com/v1";
            } else upstream=provider.path("base_url").asText(null);
        }
        if(historyProxy!=null && historyProxy.baseUrl().equals(upstream)) upstream=historyProxy.upstream();
        String identity=options.getModelRuntimeKey()+"\n"+target.provider()+"\n"+target.model()+"\n"+upstream+"\n"+accountIdentity;
        boolean standaloneSearch=!managed && ("openai".equals(target.provider())
                || effective.path("model_providers").path(target.provider()).path("supports_standalone_web_search").asBoolean(false));
        // Native image tools share openai_base_url with Responses, but use separate endpoints.
        boolean imageGeneration=!managed && "openai".equals(target.provider());
        if(historyProxy==null) historyProxy=new ResponsesCompatibilityProxy(properties.getDataDir(),options.getWorkspace(),upstream,identity,objectMapper,standaloneSearch,imageGeneration);
        else historyProxy.verifyIdentity(identity);
        if(managed && options.getModelRuntime().getSchemaVersion()>=3)historyProxy.requireMetering();
        config.withObject("features").put("responses_websockets",false).put("responses_websockets_v2",false);
        if(!managed && "openai".equals(target.provider())) {
            config.put("openai_base_url",historyProxy.baseUrl());
            if(historyStartupBase==null) {
                // Pin the built-in provider's dedicated base URL override at process startup too.
                // chatgpt_base_url is not the model transport override. Authentication stays native.
                if(!threadWorkspaces.isEmpty()) throw new CodexException("HISTORY_INCOMPATIBLE: Cannot reconfigure a loaded transport");
                historyStartupBase=historyProxy.baseUrl();
                synchronized(lifecycleLock) {
                    Process discovery=process;process=null;writer=null;stopProcessTree(discovery);
                }
            }
            return; // Built-in provider IDs cannot be overridden in model_providers.
        }
        ObjectNode provider=config.withObject("model_providers").withObject(target.provider());
        if(!managed) {
            JsonNode original=effective.path("model_providers").path(target.provider());
            if(original.isObject()) provider.setAll((ObjectNode)original);
            if(!provider.hasNonNull("name")) provider.put("name",target.provider());
        }
        provider.put("supports_websockets",false);
        provider.put("base_url",historyProxy.baseUrl());
    }

    static void configureExpert(ObjectNode params, CodexTurnInput input, String defaultModel,
                                List<CodexSkillInput> verifiedSkills) {
        if(!input.isManagedExpert()) return;
        String model=defaultModel;
        if(model==null || model.isBlank()) throw new CodexException("Codex 未返回运行模型，无法应用专家配置，请升级 Codex");
        ObjectNode settings=params.putObject("collaborationMode").put("mode","default").putObject("settings");
        settings.put("model",model);
        String instructions=expertInstructions(input.getExpertInstructions(),verifiedSkills);
        // QuestionToolCatalog removes model mode templates which would shadow this per-turn text.
        settings.put("developer_instructions",(instructions==null ? "" : instructions+"\n\n")+ExecutionConfirmation.INSTRUCTIONS);
    }

    private static String expertInstructions(String expertInstructions,List<CodexSkillInput> verifiedSkills) {
        if(verifiedSkills.isEmpty()) return expertInstructions;
        String context=PrivateSkillContext.load(verifiedSkills);
        return (expertInstructions==null ? "" : expertInstructions+"\n\n")+context;
    }

    private List<CodexSkillInput> refreshExpertSkills(Path workspace,CodexTurnInput input) {
        if(!input.isManagedExpert()) return List.of();
        ObjectNode params=objectMapper.createObjectNode();
        params.putArray("cwds").add(workspace.toString());params.put("forceReload",true);
        JsonNode response=request("skills/list",params);
        List<CodexSkillInput> result=new ArrayList<>();
        for(var expected:input.getSkills()) {
            try {
                Path path=Path.of(expected.path()).toRealPath();
                if(!expertSkillPaths.contains(path))
                    throw new CodexException("专家 Skill 不在当前会话运行实例的加载清单中");
                JsonNode match=null;
                for(var group:response.path("data")) for(var skill:group.path("skills")) {
                    if(skill.hasNonNull("path") && Path.of(skill.path("path").asText()).toAbsolutePath().normalize().equals(path)) match=skill;
                }
                if(match==null || !match.path("enabled").asBoolean())
                    throw new CodexException("Codex 未识别或已禁用项目专家 Skill："+expected.name()+"，请检查 SKILL.md 的名称、描述及格式");
                String name=match.path("name").asText();
                if(!name.matches("[A-Za-z0-9._:-]+")) throw new CodexException("专家 Skill 的声明名称不支持显式调用");
                result.add(new CodexSkillInput(name,path.toString()));
            } catch(IOException | java.nio.file.InvalidPathException failure) {throw new CodexException("无法校验项目专家 Skill："+expected.name(),failure);}
        }
        return List.copyOf(result);
    }

    void configureSkillRoots(Path workspace,List<CodexSkillInput> skills) {
        try {
            Path allowed=com.myharness.agent.workspace.AgentStorage.directory(com.myharness.agent.workspace.AgentStorage.workspaceRoot(properties.getDataDir(),workspace),"expert-runtimes");
            java.util.Set<Path> paths=new java.util.LinkedHashSet<>();
            java.util.Set<Path> roots=new java.util.LinkedHashSet<>();
            for(var skill:skills) {
                Path path=Path.of(skill.path()).toRealPath();
                if(!path.startsWith(allowed) || !path.getFileName().toString().equals("SKILL.md"))
                    throw new CodexException("专家 Skill 必须位于当前项目的会话运行目录");
                paths.add(path);roots.add(path.getParent().getParent());
            }
            if(roots.size()>1) throw new CodexException("一次专家运行不能混合多个会话的技能目录");
            ObjectNode params=objectMapper.createObjectNode();var values=params.putArray("extraRoots");
            roots.forEach(path->values.add(path.toString()));
            request("skills/extraRoots/set",params);
            expertSkillPaths=java.util.Set.copyOf(paths);
        } catch(IOException failure) {throw new CodexException("无法配置会话专家技能目录",failure);}
    }

    /** A concrete project path, not all Device workspaces, is allowed in this profile. */
    private void configureProjectPermissions(ObjectNode params,Path workspace) {
        String profile=profileId(workspace);
        params.put("permissions",profile);
        ObjectNode config=params.withObject("config");
        config.put("default_permissions",profile);
        config.putObject("permissions").set(profile,ProjectPermissionProfile.policy(objectMapper,properties.getDataDir(),workspace));
        if("Linux".equalsIgnoreCase(System.getProperty("os.name"))) {
            var environment=config.putObject("shell_environment_policy").put("inherit","none");
            environment.putObject("set").put("PATH",System.getProperty("java.home")+"/bin:/usr/local/bin:/usr/bin:/bin")
                    .put("JAVA_HOME",System.getProperty("java.home")).put("LANG","C.UTF-8");
            try {
                String temp=com.myharness.agent.workspace.AgentStorage.executionDirectory(properties.getDataDir(),workspace).toString();
                environment.withObject("set").put("TMPDIR",temp).put("HOME",temp).put("XDG_CACHE_HOME",temp)
                        .put("NPM_CONFIG_CACHE",temp+"/npm-cache").put("MAVEN_OPTS","-Dmaven.repo.local="+temp+"/maven-repository");
            } catch(IOException failure) {throw new CodexException("Cannot prepare Linux execution environment",failure);}
        }
    }

    private void activateModelRuntime(com.myharness.agent.entity.dto.ModelRuntimeDTO runtime) {
        if(runtime==null || (runtime.getSchemaVersion()<2 && !hasText(runtime.getBaseUrl()))) return;
        synchronized(lifecycleLock) {
            if(process!=null && process.isAlive()
                    && !java.util.Objects.equals(startupModelRuntime==null?null:startupModelRuntime.getRuntimeKey(),runtime.getRuntimeKey()))
                throw new CodexException("运行中的 App Server 不能切换模型 Provider");
            startupModelRuntime=runtime;
        }
    }

    private ModelTarget configureModelTarget(ObjectNode params,CodexThreadOptions options) {
        var runtime=options.getModelRuntime();
        if(runtime!=null && runtime.getSchemaVersion()>=2 && "LOCAL_CODEX".equals(runtime.getRuntimeMode())) {
            ModelTarget target=resolveLocalModelTarget(options.getWorkspace());
            params.put("model",target.model());params.put("modelProvider",target.provider());
            return target;
        }
        configureModelProvider(params,options);
        if(hasText(options.getModel())) params.put("model",options.getModel().trim());
        if(runtime!=null && runtime.getSchemaVersion()>=2 && "MANAGED_PROVIDER".equals(runtime.getRuntimeMode())) {
            params.put("modelProvider","harness_managed");
            return new ModelTarget("harness_managed",options.getModel().trim(),true);
        }
        return new ModelTarget(null,options.getModel(),false);
    }

    private ModelTarget resolveLocalModelTarget(Path workspace) {
        ObjectNode read=objectMapper.createObjectNode();read.put("cwd",workspace.toString());
        JsonNode config=request("config/read",read);
        ObjectNode list=objectMapper.createObjectNode();list.put("limit",1000);list.put("includeHidden",true);
        ModelTarget target=selectLocalModelTarget(config,request("model/list",list));
        if(localModelCatalog==null) {
            localModelCatalog=new QuestionToolCatalog(objectMapper).localSource(config.path("config"),target.model(),QuestionToolCatalog.codexHome());
            // Discovery has no active Turn. Reload model capabilities before starting/resuming work.
            synchronized(lifecycleLock) {
                if(!threadWorkspaces.isEmpty())throw new CodexException("Cannot change question tools in a loaded Conversation");
                Process discovery=process;process=null;writer=null;stopProcessTree(discovery);
            }
        }
        return target;
    }

    static ModelTarget selectLocalModelTarget(JsonNode configResult,JsonNode modelListResult) {
        JsonNode config=configResult.path("config").isObject()?configResult.path("config"):configResult;
        String provider=config.path("model_provider").asText("openai").trim();
        if(provider.isEmpty()) provider="openai";
        String configured=config.path("model").asText(null);
        List<String> defaults=new ArrayList<>();boolean configuredAvailable=false;
        for(JsonNode item:modelListResult.path("data")) {
            String model=modelId(item);
            if(model==null) continue;
            if(model.equals(configured)) configuredAvailable=true;
            if(item.path("isDefault").asBoolean(false)) defaults.add(model);
        }
        String selected=configuredAvailable?configured:(defaults.size()==1?defaults.get(0):null);
        if(!hasText(selected)) throw new CodexException("本地 Codex 未能解析唯一可用的默认模型，已阻止运行目标切换");
        return new ModelTarget(provider,selected,true);
    }

    private static String modelId(JsonNode item) {
        for(String field:List.of("model","id","slug")) {
            String value=item.path(field).asText(null);
            if(hasText(value)) return value.trim();
        }
        return null;
    }

    private void verifyModelTarget(JsonNode response,ModelTarget expected) {
        if(expected==null || !expected.strict()) return;
        // start/resume return the activated configuration at the top level. The nested
        // Thread can still carry its persisted provider from before the runtime switch.
        String actualModel=requiredText(response,"model","Codex runtime response");
        String actualProvider=requiredText(response,"modelProvider","Codex runtime response");
        if(!expected.model().equals(actualModel) || !expected.provider().equals(actualProvider))
            throw new CodexException("Codex 激活的模型运行目标与 Device 配置不一致，已阻止执行");
    }

    static record ModelTarget(String provider,String model,boolean strict) { }

    void configureModelProvider(ObjectNode params,CodexThreadOptions options) {
        var runtime=options.getModelRuntime();
        if(runtime==null || !hasText(runtime.getBaseUrl())) return;
        if(!hasText(runtime.getApiKey()) || !hasText(runtime.getRuntimeKey())) throw new CodexException("托管模型凭据或运行标识缺失");
        JsonNode raw=params.get("config");ObjectNode config=raw instanceof ObjectNode value?value:params.putObject("config");
        config.put("model_provider","harness_managed");
        if("MANAGED_PROVIDER".equals(runtime.getRuntimeMode()) && runtime.getSchemaVersion()>=3) {
            // RESPONSES_TEXT_V1 accounts for tokens only. Codex otherwise advertises
            // cached web search by default, making even ordinary questions fail admission.
            config.put("web_search","disabled");
            config.withObject("features").put("image_generation",false);
        }
        ObjectNode provider=config.withObject("model_providers").putObject("harness_managed");
        provider.put("name",hasText(runtime.getProviderName())?runtime.getProviderName():"Harness Managed");
        provider.put("base_url",runtime.getBaseUrl());provider.put("env_key","HARNESS_MODEL_API_KEY");
        provider.put("wire_api","responses");provider.put("requires_openai_auth",false);
    }

    void configureMcpServers(ObjectNode params,CodexThreadOptions options) {
        if(!options.isIsolatedExpertRuntime()) return;
        JsonNode raw=params.get("config");
        ObjectNode config=raw instanceof ObjectNode object ? object : params.putObject("config");
        // Plugin MCP discovery is separate from the initial mcpServerStatus inventory.
        // Only the Expert's explicitly supplied skills and MCP runtimes belong to this thread.
        config.withObject("features").put("apps",false).put("plugins",false);
        if(!options.getMcpServers().isEmpty()) {
            // Harness, rather than Codex auto-review, owns the user-facing Approval Request flow.
            config.put("approvals_reviewer","user");
        }
        ObjectNode servers=config.putObject("mcp_servers");
        for(var runtime:options.getMcpServers()) {
            String code=runtime.getServerCode();
            if(code==null || !code.matches("[A-Za-z0-9_-]{1,64}")) throw new CodexException("MCP Server Code 不正确");
            if(CODEX_APPS_MCP_SERVER.equals(code)) throw new CodexException("MCP Server Code codex_apps 是 Codex 保留名称");
            ObjectNode server=servers.putObject(code);
            if("STDIO".equals(runtime.getTransportType())) {
                if(nativeWindows()) throw new CodexException("Windows 读取隔离暂不支持在宿主机启动 STDIO MCP；请使用隔离的远程 MCP 服务");
                if(runtime.getCommand()==null || runtime.getCommand().isBlank()) throw new CodexException("MCP STDIO command 不能为空");
                server.put("command",runtime.getCommand());ArrayNode args=server.putArray("args");
                if(runtime.getArgs()!=null) runtime.getArgs().forEach(args::add);
                ArrayNode env=server.putArray("env_vars");if(runtime.getEnvVars()!=null) runtime.getEnvVars().forEach(env::add);
                if("WORKSPACE".equals(runtime.getCwdMode())) server.put("cwd",options.getWorkspace().toString());
            } else if("STREAMABLE_HTTP".equals(runtime.getTransportType())) {
                if(runtime.getUrl()==null || runtime.getUrl().isBlank()) throw new CodexException("MCP Streamable HTTP URL 不能为空");
                if(nativeWindows()) WindowsExecutionTools.validateRemoteMcp(runtime.getUrl());
                server.put("url",runtime.getUrl());
                ObjectNode headers=server.putObject("http_headers");
                if(runtime.getHttpHeaders()!=null) runtime.getHttpHeaders().forEach(headers::put);
            } else throw new CodexException("不支持的 MCP transport");
            // Expert Versions pin an administrator-published MCP runtime and its tool allowlist.
            // Code Mode does not forward nested MCP prompts to this App Server client, so prompting
            // would be reported as a user rejection without producing a Harness Approval Request.
            server.put("default_tools_approval_mode","approve");
            server.put("startup_timeout_sec",runtime.getStartupTimeoutSeconds());
            server.put("tool_timeout_sec",runtime.getToolTimeoutSeconds());server.put("enabled",true);server.put("required",runtime.isRequired());
            if(runtime.getEnabledTools()!=null && !runtime.getEnabledTools().isEmpty()) {ArrayNode values=server.putArray("enabled_tools");runtime.getEnabledTools().forEach(values::add);}
            if(runtime.getDisabledTools()!=null && !runtime.getDisabledTools().isEmpty()) {ArrayNode values=server.putArray("disabled_tools");runtime.getDisabledTools().forEach(values::add);}
        }
    }

    void disableInheritedMcpServers(ObjectNode threadParams,CodexThreadOptions options) {
        if(!options.isIsolatedExpertRuntime()) return;
        java.util.Set<String> allowed=options.getMcpServers().stream().map(com.myharness.agent.entity.dto.McpRuntimeDTO::getServerCode)
                .collect(java.util.stream.Collectors.toSet());
        ObjectNode servers=(ObjectNode)threadParams.path("config").path("mcp_servers");
        for(JsonNode status:mcpStatuses(null)) {
            String name=status.path("name").asText();
            if(CODEX_APPS_MCP_SERVER.equals(name)) continue;
            if(!name.isBlank() && !allowed.contains(name)) {
                // Session MCP overrides replace the complete server entry instead of deep-merging it.
                // Keep the disabled entry parseable without copying inherited commands, arguments or secrets.
                servers.putObject(name).put("command",DISABLED_INHERITED_MCP_COMMAND).put("enabled",false);
            }
        }
    }

    private void verifyMcpIsolation(String threadId,CodexThreadOptions options) {
        if(!options.isIsolatedExpertRuntime()) return;
        java.util.Set<String> allowed=options.getMcpServers().stream().map(com.myharness.agent.entity.dto.McpRuntimeDTO::getServerCode)
                .collect(java.util.stream.Collectors.toSet());
        for(JsonNode status:mcpStatuses(threadId)) {
            String name=status.path("name").asText();JsonNode runtime=status.get("runtimeStatus");
            boolean disabled=runtime==null || runtime.isNull() || "disabled".equals(runtime.asText());
            if(!allowed.contains(name) && !disabled)
                throw new CodexException("检测到未由当前 Expert 授权的 MCP Server："+name);
        }
    }

    private List<JsonNode> mcpStatuses(String threadId) {
        List<JsonNode> result=new ArrayList<>();String cursor=null;
        do {
            ObjectNode params=objectMapper.createObjectNode();params.put("detail","toolsAndAuthOnly");params.put("limit",1000);
            if(threadId==null) params.putNull("threadId"); else params.put("threadId",threadId);
            if(cursor==null) params.putNull("cursor"); else params.put("cursor",cursor);
            JsonNode response=request("mcpServerStatus/list",params);
            response.path("data").forEach(result::add);cursor=response.path("nextCursor").isTextual()?response.path("nextCursor").asText():null;
        } while(cursor!=null && !cursor.isBlank());
        return result;
    }

    private void verifyPermissionProfile(JsonNode response,Path workspace) {
        if(!profileId(workspace).equals(response.path("activePermissionProfile").path("id").asText())) {
            throw new CodexException("Codex did not activate the restricted project permission profile; upgrade Codex and remove conflicting legacy sandbox settings");
        }
    }

    @Override
    public String startTurn(String threadId, CodexTurnInput input, CodexEventListener listener) {
        requireText(threadId, "Codex thread ID");
        if (input == null || (!hasText(input.getMessage()) && input.getLocalImages().isEmpty())) {
            throw new CodexException("Turn message must not be blank");
        }
        if (listener == null) {
            throw new CodexException("Codex event listener is required");
        }
        Path workspace = threadWorkspaces.get(threadId);
        if (workspace == null) {
            throw new CodexException("Unknown Codex thread: " + threadId);
        }

        List<CodexSkillInput> expertSkills=refreshExpertSkills(workspace,input);
        ObjectNode params = objectMapper.createObjectNode();
        params.put("threadId", threadId);
        params.put("cwd", workspace.toString());
        params.put("approvalPolicy", INTERACTIVE_APPROVAL_POLICY);
        // Inherit the profile already verified on thread/start or thread/resume.
        // A turn-level profile name triggers a fresh config load without the thread's inline permissions table.
        ArrayNode inputs = params.putArray("input");
        if(hasText(input.getMessage())) {
            ObjectNode text = inputs.addObject();
            text.put("type", "text");
            text.put("text", input.getMessage());
        }
        for(String imagePath:input.getLocalImages()) {
            ObjectNode image=inputs.addObject();image.put("type","localImage");image.put("path",imagePath);
        }
        configureExpert(params,input,threadModels.get(threadId),expertSkills);
        if(input.isOrchestration())orchestrationThreads.add(threadId);else orchestrationThreads.remove(threadId);
        listenersByThread.put(threadId, listener);
        try {
            if(input.isManagedUsage() || startupModelRuntime!=null && "MANAGED_PROVIDER".equals(startupModelRuntime.getRuntimeMode()) && startupModelRuntime.getSchemaVersion()>=3) {
                if(historyProxy==null || usageClient==null || input.getHarnessTurnId()==null)throw new CodexException("托管模型计量模块不可用，请配套升级 Agent");
                historyProxy.meter(usageClient,input.getHarnessTurnId(),message->Thread.ofVirtual().start(()->{
                    runtimeFailed=true;
                    stopProcessTree(process);
                    failRuntime(message,null);
                    listener.onCompleted(null,"failed",message);
                }));
            }
            if(historyProxy!=null) historyProxy.onNotice(notice->listener.onEvent(new CodexEvent(TurnEventType.WARNING,null,notice,
                    objectMapper.createObjectNode().put("type","historyCompatibility").put("policy",ResponsesHistoryPolicy.POLICY))));
            JsonNode result = request("turn/start", params);
            String turnId = requiredText(result.path("turn"), "id", "turn/start response");
            listenersByTurn.put(turnId, listener);
            return turnId;
        } catch (RuntimeException exception) {
            listenersByThread.remove(threadId, listener);
            orchestrationThreads.remove(threadId);
            throw exception;
        }
    }

    @Override
    public void interruptTurn(String threadId, String turnId) {
        requireText(threadId, "Codex thread ID");
        requireText(turnId, "Codex turn ID");
        cancelledNativeTurns.add(turnId);stopNativeCommand(turnId);
        ObjectNode params = objectMapper.createObjectNode();
        params.put("threadId", threadId);
        params.put("turnId", turnId);
        request("turn/interrupt", params);
    }

    @Override
    public void resolveApproval(String requestId, ApprovalDecision decision) {
        resolveApproval(requestId,decision,null);
    }
    @Override public void resolveApproval(String requestId, ApprovalDecision decision, JsonNode suppliedAnswers) {
        requireText(requestId, "Approval request ID");
        if (decision == null) {
            throw new CodexException("Approval decision is required");
        }
        PendingApproval approval = pendingApprovals.get(requestId);
        if (approval == null) {
            throw new CodexException("Unknown or already resolved approval request: " + requestId);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", approval.jsonRpcId);
        ObjectNode result = response.putObject("result");
        if(decision==ApprovalDecision.ACCEPT_FOR_SESSION) throw new CodexException("审批仅支持本次决定");
        if(approval.type==ApprovalType.EXECUTION_CONFIRMATION) ExecutionConfirmation.answer(result,decision);
        else if(approval.type==ApprovalType.MCP_TOOL_CALL) {
            if(decision==ApprovalDecision.ACCEPT) result.set("answers",ToolQuestionAnswers.validate(approval.params,suppliedAnswers));
            else {
                ObjectNode empty=result.putObject("answers");
                for(JsonNode question:approval.params.path("questions")) empty.putObject(requiredText(question,"id","question")).putArray("answers");
            }
        }
        else result.put("decision", toCodexDecision(decision));
        if(!pendingApprovals.remove(requestId,approval)) throw new CodexException("审批已处理或失效");
        write(response);
        if(decision==ApprovalDecision.CANCEL && (approval.type==ApprovalType.EXECUTION_CONFIRMATION || approval.type==ApprovalType.MCP_TOOL_CALL))
            interruptTurn(requiredText(approval.params,"threadId","approval"),requiredText(approval.params,"turnId","approval"));
    }

    JsonNode request(String method, JsonNode params) {
        ensureStarted();
        long id = requestSequence.incrementAndGet();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        ObjectNode request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.set("params", params);
        try {
            write(request);
            return future.get(properties.getCodexRequestTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CodexException("Interrupted while waiting for Codex method " + method, exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            throw new CodexException("Codex method failed: " + method + ": "
                    + CodexDiagnostics.redact(cause.getMessage()), cause);
        } catch (TimeoutException exception) {
            throw new CodexException("Timed out waiting for Codex method " + method, exception);
        } finally {
            pendingRequests.remove(id);
        }
    }

    private void ensureStarted() {
        Process current = process;
        if (current != null && current.isAlive()) {
            return;
        }
        synchronized (lifecycleLock) {
            current = process;
            if (current != null && current.isAlive()) {
                return;
            }
            closing = false;
            try {
                Path modelCatalog=startupModelCatalog();
                if(modelCatalog!=null)modelCatalog=new QuestionToolCatalog(objectMapper).project(modelCatalog,properties.getDataDir().resolve("model-catalogs"));
                List<String> command = CodexProcessCommand.appServer(properties.getCodexCommand(),
                        properties.isStrictProjectIsolation(),properties.getWindowsSandbox(),modelCatalog);
                LOGGER.info("Starting Codex App Server with command {} in {}", command, System.getProperty("user.dir"));
                if(historyStartupBase!=null) command=CodexProcessCommand.appServer(properties.getCodexCommand(),properties.isStrictProjectIsolation(),properties.getWindowsSandbox(),modelCatalog,
                        List.of("openai_base_url=\""+historyStartupBase+"\"","features.responses_websockets=false","features.responses_websockets_v2=false"));
                ProcessBuilder builder=new ProcessBuilder(command);
                if(startupModelRuntime!=null && (startupModelRuntime.getSchemaVersion()<2
                        || "MANAGED_PROVIDER".equals(startupModelRuntime.getRuntimeMode()))
                        && hasText(startupModelRuntime.getApiKey()))
                    builder.environment().put("HARNESS_MODEL_API_KEY",startupModelRuntime.getApiKey());
                else builder.environment().remove("HARNESS_MODEL_API_KEY");
                Process started = builder.start();
                process = started;
                runtimeFailed=false;
                writer = new BufferedWriter(new OutputStreamWriter(started.getOutputStream(), StandardCharsets.UTF_8));
                startReaders(started);
                initialize();
            } catch (IOException exception) {
                process = null;
                writer = null;
                throw new CodexException("Unable to start Codex App Server", exception);
            } catch (RuntimeException exception) {
                Process failed = process;
                process = null;
                writer = null;
                closing = true;
                stopProcessTree(failed);
                throw exception;
            }
        }
    }

    Path startupModelCatalog() {
        if(startupModelRuntime!=null&&hasText(startupModelRuntime.getBaseUrl()))
            return new ManagedModelCatalog(objectMapper).write(properties.getDataDir().resolve("model-catalogs"),startupModelRuntime);
        return localModelCatalog;
    }

    private void initialize() {
        ObjectNode params = objectMapper.createObjectNode();
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "my-harness-agent");
        clientInfo.put("title", "My Harness For Codex Agent");
        clientInfo.put("version", AgentMetadata.version());
        ObjectNode capabilities = params.putObject("capabilities");
        capabilities.put("experimentalApi", true);
        request("initialize", params);

        ObjectNode initialized = objectMapper.createObjectNode();
        initialized.put("jsonrpc", "2.0");
        initialized.put("method", "initialized");
        write(initialized);
    }

    private void startReaders(Process started) {
        CodexDiagnostics diagnostics = new CodexDiagnostics();
        Thread stdout = new Thread(() -> readStdout(started), "codex-app-server-stdout");
        stdout.setDaemon(true);
        stdout.start();
        Thread stderr = new Thread(() -> readStderr(started, diagnostics), "codex-app-server-stderr");
        stderr.setDaemon(true);
        stderr.start();
        Thread waiter = new Thread(() -> waitForExit(started, stderr, diagnostics), "codex-app-server-waiter");
        waiter.setDaemon(true);
        waiter.start();
    }

    private void readStdout(Process started) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    handleMessage(objectMapper.readTree(line));
                }
            }
        } catch (Exception exception) {
            if (!closing && process == started) {
                failRuntime("Unable to read Codex App Server output", exception);
            }
        }
    }

    private void readStderr(Process started, CodexDiagnostics diagnostics) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(started.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                diagnostics.add(line);
            }
        } catch (IOException exception) {
            if (!closing) {
                LOGGER.warn("Unable to read Codex App Server diagnostics: {}", exception.getMessage());
            }
        }
    }

    private void waitForExit(Process started, Thread stderr, CodexDiagnostics diagnostics) {
        try {
            int exitCode = started.waitFor();
            // The process can exit before the stderr reader has consumed its final error.
            stderr.join(1000);
            if (!closing && process == started) {
                failRuntime("Codex App Server exited with code " + exitCode + diagnostics.summary(), null);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    void handleMessage(JsonNode message) {
        JsonNode id = message.get("id");
        String method = textOrNull(message, "method");
        if (id != null && method == null) {
            completeResponse(id, message);
            return;
        }
        if (id != null && method != null) {
            handleServerRequest(id, method, message.path("params"));
            return;
        }
        if (method != null) {
            handleNotification(method, message.path("params"));
        }
    }

    private void completeResponse(JsonNode id, JsonNode message) {
        if (!id.canConvertToLong()) {
            return;
        }
        CompletableFuture<JsonNode> future = pendingRequests.get(id.asLong());
        if (future == null) {
            return;
        }
        if (message.hasNonNull("error")) {
            future.completeExceptionally(new CodexException(message.path("error").path("message").asText("Unknown error")));
        } else {
            future.complete(message.path("result"));
        }
    }

    private void handleServerRequest(JsonNode id, String method, JsonNode params) {
        if("item/tool/call".equals(method) && OrchestrationOutcomeTool.NAME.equals(params.path("tool").asText())) {
            String threadId=params.path("threadId").asText();
            var target=listener(params);
            if(!orchestrationThreads.contains(threadId) || target==null || listenersByThread.get(threadId)!=target
                || listenersByTurn.get(params.path("turnId").asText())!=target
                || cancelledNativeTurns.contains(params.path("turnId").asText())) {
                sendErrorResponse(id,-32602,"No active orchestration node");return;
            }
            var response=objectMapper.createObjectNode().put("jsonrpc","2.0");response.set("id",id.deepCopy());
            var result=response.putObject("result");
            try {
                OrchestrationOutcomeTool.validate(params.path("arguments"));
                target.onNodeOutcome(params.path("arguments").deepCopy());
                result.put("success",true).putArray("contentItems").addObject().put("type","inputText").put("text","Outcome recorded. The platform validates the node after this turn ends.");
            } catch(RuntimeException failure) {
                result.put("success",false).putArray("contentItems").addObject().put("type","inputText").put("text","Invalid node outcome");
            }
            write(response);return;
        }
        if("item/tool/call".equals(method) && nativeWindows()) {
            handleNativeCommand(id,params);return;
        }
        ApprovalType type;
        if ("item/commandExecution/requestApproval".equals(method)) {
            type = ApprovalType.COMMAND_EXECUTION;
        } else if ("item/fileChange/requestApproval".equals(method)) {
            type = ApprovalType.FILE_CHANGE;
        } else if ("item/tool/requestUserInput".equals(method)) {
            type = ExecutionConfirmation.isConfirmation(params) ? ApprovalType.EXECUTION_CONFIRMATION : ApprovalType.MCP_TOOL_CALL;
        } else {
            sendUnsupportedRequest(id, method);
            return;
        }
        if(type==ApprovalType.EXECUTION_CONFIRMATION) {
            try { ExecutionConfirmation.validate(params); }
            catch(CodexException failure) {sendErrorResponse(id,-32602,failure.getMessage());return;}
        }
        if (properties.isStrictProjectIsolation() && (type==ApprovalType.COMMAND_EXECUTION || type==ApprovalType.FILE_CHANGE)) {
            ObjectNode response=objectMapper.createObjectNode();
            response.put("jsonrpc","2.0"); response.set("id",id.deepCopy());
            response.putObject("result").put("decision","decline");
            write(response);
            CodexEventListener target=listener(params);
            if(target!=null) {
                ObjectNode blocked=objectMapper.createObjectNode().put("type","approvalBlocked")
                        .put("approvalType",type.name()).put("policyCode","STRICT_PROJECT_ISOLATION")
                        .put("message","平台策略已拦截此请求：严格项目隔离禁止通过命令或文件审批扩大执行权限。此决定由平台作出，并非用户拒绝。")
                        .put("guidance","如需用户确认项目内操作，请使用执行前确认；批准后仍须遵守原有项目权限。");
                blocked.set("operation",params.deepCopy());
                target.onEvent(new CodexEvent(TurnEventType.WARNING,"policy-blocked-"+java.util.UUID.randomUUID(),blocked.toString(),blocked));
            }
            LOGGER.warn("Declined unexpected approval request in strict project isolation mode: {}",method);
            return;
        }
        String requestId = id.asText();
        PendingApproval approval = new PendingApproval(id.deepCopy(),type,params.deepCopy());
        if (pendingApprovals.putIfAbsent(requestId, approval) != null) {
            sendErrorResponse(id, -32600, "Duplicate approval request ID");
            return;
        }
        CodexEventListener listener = listener(params);
        if (listener == null) {
            pendingApprovals.remove(requestId);
            sendErrorResponse(id, -32602, "Approval does not belong to an active turn");
            return;
        }
        listener.onApproval(new CodexApproval(requestId, type, params.deepCopy()));
    }

    private void handleNativeCommand(JsonNode id,JsonNode params) {
        String threadId=params.path("threadId").asText();String turnId=params.path("turnId").asText();
        Path workspace=threadWorkspaces.get(threadId);
        String tool=params.path("tool").asText();
        if(!List.of(WindowsExecutionTools.NAME,WorkspacePatchTool.NAME,WorkspaceImageTool.NAME,WorkspaceImageGenerationTool.NAME,WindowsCommandTool.NAME).contains(tool) || workspace==null || listener(params)==null || turnId.isBlank() || cancelledNativeTurns.contains(turnId)) {
            sendErrorResponse(id,-32602,"Unknown isolated command or inactive turn");return;
        }
        Thread worker=Thread.ofVirtual().unstarted(()-> {
            ObjectNode response=objectMapper.createObjectNode().put("jsonrpc","2.0");response.set("id",id.deepCopy());
            ObjectNode result=response.putObject("result");String output=null;
            try {
                if(WindowsCommandTool.NAME.equals(tool)) {
                    var execution=WindowsCommandTool.execute(workspace,properties,params.path("arguments"),objectMapper);
                    result.put("success",execution.exitCode()==0);output="Exit code: "+execution.exitCode()+"\n"+execution.output();
                } else if(WorkspaceImageGenerationTool.NAME.equals(tool)) {
                    if(!controlledImages())throw new CodexException("当前模型未接通图片生成服务");
                    result.setAll(WorkspaceImageGenerationTool.generate(workspace,params.path("arguments"),objectMapper,
                            (payload,edit)->historyProxy.generateControlledImage(payload,edit,turnId)));
                } else if(WorkspaceImageTool.NAME.equals(tool)) {
                    if(!imageInputThreads.contains(threadId))throw new CodexException("当前模型未配置图片输入能力");
                    result.setAll(WorkspaceImageTool.view(workspace,WindowsExecutionTools.textArgument(params.path("arguments"),"path",2048),objectMapper));
                } else if(WorkspacePatchTool.NAME.equals(tool)) {
                    output=WorkspacePatchTool.apply(workspace,WindowsExecutionTools.textArgument(params.path("arguments"),"patch",131072));result.put("success",true);
                } else {
                    var execution=WindowsExecutionTools.execute(workspace,properties,params.path("arguments"));
                    result.put("success",execution.exitCode()==0);output="Exit code: "+execution.exitCode()+"\n"+execution.output();
                }
            } catch(Exception failure) {result.put("success",false);output=CodexDiagnostics.redact(failure.getMessage());}
            finally {nativeCommands.remove(turnId,Thread.currentThread());}
            if(output!=null)result.putArray("contentItems").addObject().put("type","inputText").put("text",output);
            if(!closing) write(response);
        });
        if(nativeCommands.putIfAbsent(turnId,worker)!=null) {sendErrorResponse(id,-32602,"Only one isolated command may run per turn");return;}
        worker.start();
    }

    private void stopNativeCommand(String turnId) {
        Thread worker=nativeCommands.get(turnId);
        if(worker==null || worker==Thread.currentThread()) return;
        worker.interrupt();
        try {worker.join(5000);} catch(InterruptedException failure) {Thread.currentThread().interrupt();throw new CodexException("Interrupted while stopping isolated command",failure);}
        if(worker.isAlive()) throw new CodexException("Cannot confirm isolated command termination");
    }

    private void handleNotification(String method, JsonNode params) {
        if ("turn/completed".equals(method)) {
            String turnId = params.path("turn").path("id").asText(null);
            if(turnId!=null) {cancelledNativeTurns.add(turnId);stopNativeCommand(turnId);}
            if(turnId!=null) pendingApprovals.entrySet().removeIf(entry -> turnId.equals(entry.getValue().params.path("turnId").asText()));
            String status = params.path("turn").path("status").asText("failed");
            String reason = params.path("turn").path("error").path("message").asText(null);
            CodexEventListener listener = turnId == null ? listener(params) : listenersByTurn.remove(turnId);
            if (listener != null) {
                listener.onCompleted(turnId, status, reason);
            }
            String threadId = textOrNull(params, "threadId");
            if (threadId != null && listener != null) {
                listenersByThread.remove(threadId, listener);
                orchestrationThreads.remove(threadId);
            }
            if(turnId!=null) cancelledNativeTurns.remove(turnId);
            return;
        }
        if ("error".equals(method)) {
            CodexEventListener listener = listener(params);
            if (listener != null) {
                listener.onEvent(new CodexEvent(TurnEventType.WARNING, null,
                        params.path("error").path("message").asText("Codex turn error"), params.deepCopy()));
            }
            return;
        }
        CodexEvent event = translateEvent(method, params);
        CodexEventListener listener = listener(params);
        if (event != null && listener != null) {
            listener.onEvent(event);
        }
    }

    CodexEvent translateEvent(String method, JsonNode params) {
        String itemId = textOrNull(params, "itemId");
        if ("item/agentMessage/delta".equals(method)) {
            String phase = textOrNull(params, "phase");
            if (phase == null && itemId != null) phase = messagePhasesByItem.get(itemId);
            return event(TurnEventType.AGENT_MESSAGE_DELTA, itemId, params.path("delta").asText(), phase, params);
        }
        if ("item/plan/delta".equals(method)) {
            return event(TurnEventType.PLAN_DELTA, itemId, params.path("delta").asText(), params);
        }
        if ("item/commandExecution/outputDelta".equals(method)) {
            return event(TurnEventType.COMMAND_OUTPUT_DELTA, itemId, params.path("delta").asText(), params);
        }
        if ("item/fileChange/outputDelta".equals(method)) {
            return event(TurnEventType.FILE_CHANGE_DELTA, itemId, params.path("delta").asText(), params);
        }
        if ("turn/diff/updated".equals(method)) {
            return event(TurnEventType.TURN_DIFF_UPDATED, null, params.path("diff").asText(), params);
        }
        if ("warning".equals(method)) {
            return event(TurnEventType.WARNING, null, params.path("message").asText(), params);
        }
        if ("item/started".equals(method) || "item/completed".equals(method)) {
            JsonNode item = WindowsExecutionTools.eventSummary(params.path("item"),objectMapper);
            String type = item.path("type").asText();
            boolean started = "item/started".equals(method);
            String eventItemId = item.path("id").asText(null);
            String phase = "agentMessage".equals(type) ? textOrNull(item, "phase") : null;
            if (started && eventItemId != null && phase != null) messagePhasesByItem.put(eventItemId, phase);
            if (phase == null && eventItemId != null) phase = messagePhasesByItem.get(eventItemId);
            CodexEvent event = event(itemEventType(type, started), eventItemId, null, phase, item);
            if (!started && eventItemId != null) messagePhasesByItem.remove(eventItemId);
            return event;
        }
        return null;
    }

    private TurnEventType itemEventType(String itemType, boolean started) {
        if ("commandExecution".equals(itemType)) {
            return started ? TurnEventType.COMMAND_STARTED : TurnEventType.COMMAND_COMPLETED;
        }
        if ("fileChange".equals(itemType)) {
            return started ? TurnEventType.FILE_CHANGE_STARTED : TurnEventType.FILE_CHANGE_COMPLETED;
        }
        return started ? TurnEventType.ITEM_STARTED : TurnEventType.ITEM_COMPLETED;
    }

    private CodexEvent event(TurnEventType type, String itemId, String content, JsonNode details) {
        return event(type, itemId, content, null, details);
    }

    private CodexEvent event(TurnEventType type, String itemId, String content, String phase, JsonNode details) {
        return new CodexEvent(type, itemId, content, phase, details.deepCopy());
    }

    private CodexEventListener listener(JsonNode params) {
        String turnId = textOrNull(params, "turnId");
        if (turnId != null) {
            CodexEventListener listener = listenersByTurn.get(turnId);
            if (listener != null) {
                return listener;
            }
        }
        String threadId = textOrNull(params, "threadId");
        return threadId == null ? null : listenersByThread.get(threadId);
    }

    private void sendUnsupportedRequest(JsonNode id, String method) {
        sendErrorResponse(id, -32601, "Unsupported App Server request: " + method);
    }

    private void sendErrorResponse(JsonNode id, int code, String message) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        write(response);
    }

    void write(JsonNode message) {
        BufferedWriter currentWriter = writer;
        if (currentWriter == null) {
            throw new CodexException("Codex App Server is not running");
        }
        synchronized (writeLock) {
            try {
                currentWriter.write(objectMapper.writeValueAsString(message));
                currentWriter.newLine();
                currentWriter.flush();
            } catch (IOException exception) {
                throw new CodexException("Unable to write to Codex App Server", exception);
            }
        }
    }

    private void failRuntime(String message, Throwable cause) {
        runtimeFailed=true;
        CodexException failure = cause == null ? new CodexException(message) : new CodexException(message, cause);
        for (CompletableFuture<JsonNode> future : pendingRequests.values()) {
            future.completeExceptionally(failure);
        }
        for (Map.Entry<String, CodexEventListener> entry : listenersByTurn.entrySet()) {
            entry.getValue().onCompleted(entry.getKey(), "failed", message);
        }
        listenersByTurn.clear();
        listenersByThread.clear();
        pendingApprovals.clear();
        LOGGER.error(message);
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        closing = true;
        for(String turnId:nativeCommands.keySet()) {cancelledNativeTurns.add(turnId);stopNativeCommand(turnId);}
        cancelledNativeTurns.clear();
        Process current = process;
        writer = null;
        stopProcessTree(current);
        process = null;
        if(historyProxy!=null) {historyProxy.close();historyProxy=null;}
        CodexException closed = new CodexException("Codex App Server was stopped");
        for (CompletableFuture<JsonNode> future : pendingRequests.values()) {
            future.completeExceptionally(closed);
        }
        pendingRequests.clear();
        listenersByTurn.clear();
        listenersByThread.clear();
        pendingApprovals.clear();
        messagePhasesByItem.clear();
        threadWorkspaces.clear();
        threadModels.clear();
    }

    private List<ProcessHandle> stoppingProcesses=List.of();
    private void stopProcessTree(Process current) {
        if (current == null && stoppingProcesses.isEmpty()) {
            return;
        }
        // Capture ownership before the wrapper exits; Windows .cmd launchers have multiple descendants.
        List<ProcessHandle> owned = new ArrayList<>(stoppingProcesses);
        if(current!=null) {
            for(ProcessHandle child:current.descendants().toList())if(!owned.contains(child))owned.add(child);
            if(!owned.contains(current.toHandle()))owned.add(current.toHandle());
        }
        stoppingProcesses=List.copyOf(owned);
        boolean interrupted = false;
        try {
            try {
                if(current!=null)current.getOutputStream().close();
            } catch (IOException ignored) {
                // Already closed after startup failure or a remote exit.
            }
            if(current!=null)current.waitFor(3, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            interrupted = true;
        } finally {
            for (ProcessHandle handle : owned) {
                if (handle.isAlive()) {
                    handle.destroyForcibly();
                }
            }
        }
        try {
            CompletableFuture<?>[] exits = owned.stream().map(ProcessHandle::onExit)
                    .toArray(CompletableFuture<?>[]::new);
            CompletableFuture.allOf(exits).get(3, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            interrupted = true;
        } catch (ExecutionException | TimeoutException exception) {
            LOGGER.warn("Timed out waiting for owned Codex processes to exit");
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if(owned.stream().anyMatch(ProcessHandle::isAlive))
            throw new CodexException("Owned Codex processes have not confirmed termination");
        stoppingProcesses=List.of();
    }

    private String toCodexDecision(ApprovalDecision decision) {
        switch (decision) {
            case ACCEPT: return "accept";
            case ACCEPT_FOR_SESSION: return "acceptForSession";
            case DECLINE: return "decline";
            case CANCEL: return "cancel";
            default: throw new CodexException("Unsupported approval decision: " + decision);
        }
    }

    private String requiredText(JsonNode node, String field, String source) {
        String value = node.path(field).asText(null);
        if (!hasText(value)) {
            throw new CodexException("Missing " + field + " in " + source);
        }
        return value;
    }

    private void requireText(String value, String label) {
        if (!hasText(value)) {
            throw new CodexException(label + " must not be blank");
        }
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static final class PendingApproval {
        private final JsonNode jsonRpcId;
        private final ApprovalType type;
        private final JsonNode params;

        private PendingApproval(JsonNode jsonRpcId,ApprovalType type,JsonNode params) {
            this.jsonRpcId = jsonRpcId;
            this.type = type;
            this.params = params;
        }
    }
}
