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
    private java.util.Set<Path> expertSkillPaths=java.util.Set.of();
    private static final Logger LOGGER = LoggerFactory.getLogger(AppServerCodexAdapter.class);

    private final AgentProperties properties;
    private final ObjectMapper objectMapper;
    private final AtomicLong requestSequence = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();
    private final Map<String, CodexEventListener> listenersByTurn = new ConcurrentHashMap<>();
    private final Map<String, CodexEventListener> listenersByThread = new ConcurrentHashMap<>();
    private final Map<String, Path> threadWorkspaces = new ConcurrentHashMap<>();
    private final Map<String, String> threadModels = new ConcurrentHashMap<>();
    private final Map<String, PendingApproval> pendingApprovals = new ConcurrentHashMap<>();
    private final Map<String, String> messagePhasesByItem = new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();
    private final Object writeLock = new Object();

    private volatile Process process;
    private volatile BufferedWriter writer;
    private volatile boolean closing;
    private volatile boolean runtimeFailed;
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
        ObjectNode params = objectMapper.createObjectNode();
        params.put("cwd", options.getWorkspace().toString());
        params.put("approvalPolicy", "never");
        configureProjectPermissions(params, options.getWorkspace());
        params.put("ephemeral", false);
        if(options.isIsolatedExpertRuntime()) configureSkillRoots(options.getWorkspace(),options.getExpertSkills());
        if (hasText(options.getModel())) {
            params.put("model", options.getModel().trim());
        }
        JsonNode result = request("thread/start", params);
        verifyPermissionProfile(result,options.getWorkspace());
        String threadId = requiredText(result.path("thread"), "id", "thread/start response");
        threadWorkspaces.put(threadId, options.getWorkspace());
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
        if(options.isIsolatedExpertRuntime()) configureSkillRoots(options.getWorkspace(),options.getExpertSkills());
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
        if ("active".equals(stored.path("status").path("type").asText())) {
            throw new CodexException("Cannot resume a Codex thread with an active Turn");
        }
        ObjectNode params = objectMapper.createObjectNode();
        params.put("threadId", threadId);
        params.put("cwd", options.getWorkspace().toString());
        params.put("approvalPolicy", "never");
        configureProjectPermissions(params, options.getWorkspace());
        if (hasText(options.getModel())) params.put("model", options.getModel().trim());
        JsonNode resumeResult = request("thread/resume", params);
        verifyPermissionProfile(resumeResult,options.getWorkspace());
        JsonNode resumed = resumeResult.path("thread");
        verifyThreadBinding(resumed, threadId, options.getWorkspace());
        threadWorkspaces.put(threadId, options.getWorkspace());
        rememberModel(threadId,resumeResult,options.getModel());
    }

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

    private String profileId(Path workspace) {
        return "harness-" + java.util.UUID.nameUUIDFromBytes(
                workspace.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8)).toString().replace("-","");
    }

    private void rememberModel(String threadId, JsonNode response, String fallback) {
        String model=response.path("model").asText(fallback);
        if(hasText(model)) threadModels.put(threadId,model);
    }

    static void configureExpert(ObjectNode params, CodexTurnInput input, String defaultModel) {
        if(!input.isManagedExpert()) return;
        String model=input.getModel()==null || input.getModel().isBlank() ? defaultModel : input.getModel().trim();
        if(model==null || model.isBlank()) throw new CodexException("Codex 未返回运行模型，无法应用专家配置，请升级 Codex");
        ObjectNode settings=params.putObject("collaborationMode").put("mode","default").putObject("settings");
        settings.put("model",model);
        String instructions=input.getExpertInstructions();
        if(instructions==null) settings.putNull("developer_instructions");
        else settings.put("developer_instructions",instructions);
        if(input.getReasoningEffort()==null || input.getReasoningEffort().isBlank()) settings.putNull("reasoning_effort");
        else settings.put("reasoning_effort",input.getReasoningEffort());
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
            Path allowed=workspace.toRealPath().resolve(".harness/expert-runtimes");
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
        ObjectNode config=params.putObject("config");
        ObjectNode policy=config.putObject("permissions").putObject(profile);
        ObjectNode filesystem=policy.putObject("filesystem");
        filesystem.put(":root","deny");
        filesystem.put(":minimal","read");
        filesystem.put(":tmpdir","deny");
        filesystem.put(":slash_tmp","deny");
        ObjectNode project=filesystem.putObject(workspace.toAbsolutePath().normalize().toString());
        project.put(".","write");
        project.put(".git","read");
        project.put(".codex","read");
        policy.putObject("network").put("enabled",false);
    }

    private void verifyPermissionProfile(JsonNode response,Path workspace) {
        if(!profileId(workspace).equals(response.path("activePermissionProfile").path("id").asText())) {
            throw new CodexException("Codex did not activate the restricted project permission profile; upgrade Codex and remove conflicting legacy sandbox settings");
        }
    }

    @Override
    public String startTurn(String threadId, CodexTurnInput input, CodexEventListener listener) {
        requireText(threadId, "Codex thread ID");
        if (input == null || !hasText(input.getMessage())) {
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
        params.put("approvalPolicy", "never");
        // Inherit the profile already verified on thread/start or thread/resume.
        // A turn-level profile name triggers a fresh config load without the thread's inline permissions table.
        ArrayNode inputs = params.putArray("input");
        ObjectNode text = inputs.addObject();
        text.put("type", "text");
        String message=input.getMessage();
        if(!expertSkills.isEmpty()) message+="\n\n"+expertSkills.stream().map(skill->"$"+skill.name()).collect(java.util.stream.Collectors.joining(" "));
        text.put("text", message);
        for(var skill:expertSkills) inputs.addObject().put("type","skill").put("name",skill.name()).put("path",skill.path());
        if (hasText(input.getModel())) {
            params.put("model", input.getModel().trim());
        }
        if (hasText(input.getReasoningEffort())) {
            params.put("effort", input.getReasoningEffort().trim());
        }

        configureExpert(params,input,threadModels.get(threadId));
        listenersByThread.put(threadId, listener);
        try {
            JsonNode result = request("turn/start", params);
            String turnId = requiredText(result.path("turn"), "id", "turn/start response");
            listenersByTurn.put(turnId, listener);
            if(hasText(input.getModel())) threadModels.put(threadId,input.getModel().trim());
            return turnId;
        } catch (RuntimeException exception) {
            listenersByThread.remove(threadId, listener);
            throw exception;
        }
    }

    @Override
    public void interruptTurn(String threadId, String turnId) {
        requireText(threadId, "Codex thread ID");
        requireText(turnId, "Codex turn ID");
        ObjectNode params = objectMapper.createObjectNode();
        params.put("threadId", threadId);
        params.put("turnId", turnId);
        request("turn/interrupt", params);
    }

    @Override
    public void resolveApproval(String requestId, ApprovalDecision decision) {
        requireText(requestId, "Approval request ID");
        if (decision == null) {
            throw new CodexException("Approval decision is required");
        }
        PendingApproval approval = pendingApprovals.remove(requestId);
        if (approval == null) {
            throw new CodexException("Unknown or already resolved approval request: " + requestId);
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", approval.jsonRpcId);
        ObjectNode result = response.putObject("result");
        result.put("decision", toCodexDecision(decision));
        write(response);
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
                List<String> command = CodexProcessCommand.appServer(properties.getCodexCommand(),
                        properties.isStrictProjectIsolation(),properties.getWindowsSandbox());
                LOGGER.info("Starting Codex App Server with command {} in {}", command, System.getProperty("user.dir"));
                Process started = new ProcessBuilder(command).start();
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
            if (!closing) {
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

    private void handleMessage(JsonNode message) {
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
        ApprovalType type;
        if ("item/commandExecution/requestApproval".equals(method)) {
            type = ApprovalType.COMMAND_EXECUTION;
        } else if ("item/fileChange/requestApproval".equals(method)) {
            type = ApprovalType.FILE_CHANGE;
        } else {
            sendUnsupportedRequest(id, method);
            return;
        }
        if (properties.isStrictProjectIsolation()) {
            ObjectNode response=objectMapper.createObjectNode();
            response.put("jsonrpc","2.0"); response.set("id",id.deepCopy());
            response.putObject("result").put("decision","decline");
            write(response);
            LOGGER.warn("Declined unexpected approval request in strict project isolation mode: {}",method);
            return;
        }
        String requestId = id.asText();
        PendingApproval approval = new PendingApproval(id.deepCopy(), type);
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

    private void handleNotification(String method, JsonNode params) {
        if ("turn/completed".equals(method)) {
            String turnId = params.path("turn").path("id").asText(null);
            String status = params.path("turn").path("status").asText("failed");
            String reason = params.path("turn").path("error").path("message").asText(null);
            CodexEventListener listener = turnId == null ? listener(params) : listenersByTurn.remove(turnId);
            if (listener != null) {
                listener.onCompleted(turnId, status, reason);
            }
            String threadId = textOrNull(params, "threadId");
            if (threadId != null && listener != null) {
                listenersByThread.remove(threadId, listener);
            }
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
            JsonNode item = params.path("item");
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

    private void write(JsonNode message) {
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
    public void close() {
        closing = true;
        Process current = process;
        process = null;
        writer = null;
        stopProcessTree(current);
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

    private void stopProcessTree(Process current) {
        if (current == null) {
            return;
        }
        // Capture ownership before the wrapper exits; Windows .cmd launchers have multiple descendants.
        List<ProcessHandle> owned = new ArrayList<>(current.descendants().toList());
        owned.add(current.toHandle());
        boolean interrupted = false;
        try {
            try {
                current.getOutputStream().close();
            } catch (IOException ignored) {
                // Already closed after startup failure or a remote exit.
            }
            current.waitFor(3, TimeUnit.SECONDS);
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

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static final class PendingApproval {
        private final JsonNode jsonRpcId;
        private final ApprovalType type;

        private PendingApproval(JsonNode jsonRpcId, ApprovalType type) {
            this.jsonRpcId = jsonRpcId;
            this.type = type;
        }
    }
}
