package com.myharness.agent.codex;

import com.myharness.agent.command.AgentOperationException;
import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.connection.AgentEvent;
import com.myharness.agent.connection.AgentEventBus;
import com.myharness.agent.entity.dto.ApprovalRequiredEventDTO;
import com.myharness.agent.entity.dto.ApprovalResolvedEventDTO;
import com.myharness.agent.entity.dto.InterruptTurnCommandDTO;
import com.myharness.agent.entity.dto.ResolveApprovalCommandDTO;
import com.myharness.agent.entity.dto.StartThreadCommandDTO;
import com.myharness.agent.entity.dto.StartTurnCommandDTO;
import com.myharness.agent.entity.dto.ThreadStartedEventDTO;
import com.myharness.agent.entity.dto.ExpertRuntimeUpdatedEventDTO;
import com.myharness.agent.entity.dto.TurnEventDTO;
import com.myharness.agent.entity.dto.TurnStartedEventDTO;
import com.myharness.agent.entity.dto.TurnTerminalEventDTO;
import com.myharness.agent.entity.enums.AgentEventType;
import com.myharness.agent.workspace.WorkspaceAccessException;
import com.myharness.agent.workspace.WorkspaceRegistry;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class AgentSessionManager {
    private final ExpertSkillPreparation expertSkills;
    private final CodexGateway codexGateway;
    private final com.myharness.agent.attachment.ConversationAttachmentService attachments;
    private final com.myharness.agent.artifact.ConversationArtifactService artifacts;
    private volatile boolean acceptingTurns=true;
    private final java.util.concurrent.atomic.AtomicLong connectionEpoch=new java.util.concurrent.atomic.AtomicLong();
    public void connected(){acceptingTurns=true;}
    private final java.util.Set<String> canceledTurns=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> attemptedAttachmentTurns=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final WorkspaceRegistry workspaceRegistry;
    private final AgentEventBus eventBus;
    private final Semaphore turnPermits;
    private final Map<String, SessionContext> sessions = new ConcurrentHashMap<>();
    private final Map<String, Path> projectRoots = new ConcurrentHashMap<>();
    private final Map<Path, String> rootProjects = new ConcurrentHashMap<>();
    private final Object isolationLock = new Object();

    public AgentSessionManager(CodexGateway codexGateway, WorkspaceRegistry workspaceRegistry,
                               AgentEventBus eventBus, AgentProperties properties, com.myharness.agent.attachment.ConversationAttachmentService attachments,
                               com.myharness.agent.artifact.ConversationArtifactService artifacts, ExpertSkillPreparation expertSkills) {
        this.expertSkills=expertSkills;
        this.codexGateway = codexGateway; this.attachments=attachments; this.artifacts=artifacts;
        this.workspaceRegistry = workspaceRegistry;
        this.eventBus = eventBus;
        this.turnPermits = new Semaphore(properties.getMaxConcurrentTurns());
    }

    public AgentEvent startThread(StartThreadCommandDTO command) {
        synchronized (isolationLock) {
            return createThread(command);
        }
    }

    private AgentEvent createThread(StartThreadCommandDTO command) {
        require(command == null ? null : command.getProjectId(), "projectId");
        require(command == null ? null : command.getConversationId(), "conversationId");
        require(command.getWorkspaceName(), "workspaceName");
        if (sessions.containsKey(command.getConversationId())) {
            throw new AgentOperationException("CONVERSATION_ALREADY_STARTED",
                    "Conversation already has a Codex thread: " + command.getConversationId());
        }
        final Path workspace;
        try {
            workspace = workspaceRegistry.resolve(command.getWorkspaceName(), "").toAbsolutePath().normalize();
        } catch (WorkspaceAccessException exception) {
            throw new AgentOperationException("WORKSPACE_NOT_ALLOWED", exception.getMessage(), exception);
        }
        reserveProjectRoot(command.getProjectId(),workspace);
        final String codexThreadId;
        try {
            codexThreadId = codexGateway.startThread(new CodexThreadOptions(command.getProjectId(),workspace,command.getModel()));
        } catch (RuntimeException exception) {
            releaseUnusedProjectRoot(command.getProjectId(),workspace);
            throw exception;
        }
        SessionContext context = new SessionContext(command.getProjectId(),command.getConversationId(), codexThreadId, workspace);
        SessionContext existing = sessions.putIfAbsent(command.getConversationId(), context);
        if (existing != null) {
            codexGateway.closeThread(codexThreadId);
            throw new AgentOperationException("CONVERSATION_ALREADY_STARTED",
                    "Conversation was started concurrently: " + command.getConversationId());
        }
        return new AgentEvent(AgentEventType.THREAD_STARTED, command.getConversationId(),
                new ThreadStartedEventDTO(command.getConversationId(), codexThreadId));
    }

    public AgentEvent startTurn(StartTurnCommandDTO command) {
        long epoch=connectionEpoch.get();
        if(!acceptingTurns) throw new AgentOperationException("AGENT_DISCONNECTED","Agent 已断开连接");
        require(command == null ? null : command.getConversationId(), "conversationId");
        require(command.getTurnId(), "turnId");
        if(command.getAttachments().isEmpty()) require(command.getMessage(), "message");
        if(canceledTurns.contains(command.getTurnId())) return new AgentEvent(AgentEventType.TURN_INTERRUPTED,command.getTurnId(),
                new TurnTerminalEventDTO(command.getConversationId(),command.getTurnId(),null,"已取消",0L));
        if (!turnPermits.tryAcquire()) {
            throw new AgentOperationException("AGENT_BUSY", "Agent has reached its concurrent Turn limit");
        }
        final SessionContext session;
        try {
            session = sessionForTurn(command);
        } catch (RuntimeException exception) {
            turnPermits.release();
            throw exception;
        }

        ActiveTurn active = new ActiveTurn(command.getTurnId());
        active.runner=Thread.currentThread();
        synchronized (session) {
            if (session.activeTurn != null) {
                turnPermits.release();
                throw new AgentOperationException("TURN_ALREADY_RUNNING",
                        "Conversation already has an active Turn: " + command.getConversationId());
            }
            session.activeTurn = active;
        }

        try {
            CodexEventListener listener = listener(session, active);
            if(canceledTurns.contains(command.getTurnId()) || !acceptingTurns || connectionEpoch.get()!=epoch) active.preparation.cancel();
            String message=attachments.prepare(command,active.preparation);
            message=message+artifacts.instructions(command.getTurnId());
            var preparedSkills=expertSkills.prepare(command,active.preparation);
            active.preparation.check();
            if(command.getExpertRuntime()!=null) {
                prepareExpertThread(session,command,preparedSkills,active.preparation);
            }
            active.preparation.check();
            if(!command.getAttachments().isEmpty() && !attemptedAttachmentTurns.add(command.getTurnId()))
                throw new AgentOperationException("TURN_ALREADY_ATTEMPTED","附件 Turn 不能重复启动");
            attachments.claim(command);
            synchronized(active) {active.preparation.check(); active.launching=true;}
            if(!command.getAttachments().isEmpty()) listener.onEvent(new CodexEvent(com.myharness.agent.entity.enums.TurnEventType.ITEM_COMPLETED,
                    "attachment-preparation","附件已保存到项目，正在启动 Codex",null,null));
            CodexTurnInput input=new CodexTurnInput(message, command.getModel(), command.getReasoningEffort());
            if(command.getExpertRuntime()!=null) input.withExpert(command.getExpertRuntime().getSystemPrompt(),preparedSkills);
            String codexTurnId = codexGateway.startTurn(session.codexThreadId,input,listener);
            session.needsHistory=false;
            active.codexTurnId = codexTurnId;
            active.runner=null;
            if(active.preparation.canceled()) {
                codexGateway.interruptTurn(session.codexThreadId,codexTurnId);
                finish(session,active);
                return new AgentEvent(AgentEventType.TURN_INTERRUPTED,command.getTurnId(),
                        new TurnTerminalEventDTO(command.getConversationId(),command.getTurnId(),codexTurnId,"已取消",active.eventSeq));
            }
            return new AgentEvent(AgentEventType.TURN_STARTED, command.getTurnId(),
                    new TurnStartedEventDTO(command.getConversationId(), command.getTurnId(), codexTurnId));
        } catch (RuntimeException exception) {
            try {
                if(!command.getAttachments().isEmpty() && !active.preparation.canceled()) {
                    listener(session,active).onEvent(new CodexEvent(com.myharness.agent.entity.enums.TurnEventType.WARNING,
                            "attachment-preparation-error",exception.getMessage(),null,null));
                }
            } catch(RuntimeException publishFailure) {exception.addSuppressed(publishFailure);}
            finally {finish(session, active);}
            if(active.preparation.canceled()) {
                Thread.interrupted();
                return new AgentEvent(AgentEventType.TURN_INTERRUPTED,command.getTurnId(),
                        new TurnTerminalEventDTO(command.getConversationId(),command.getTurnId(),active.codexTurnId,"附件准备已取消",active.eventSeq));
            }
            throw exception;
        }
    }

    public void interruptTurn(InterruptTurnCommandDTO command) {
        require(command == null ? null : command.getConversationId(), "conversationId");
        require(command.getTurnId(), "turnId");
        canceledTurns.add(command.getTurnId());
        SessionContext session = sessions.get(command.getConversationId());
        if(session==null) return;
        ActiveTurn active;
        synchronized (session) {
            active = session.activeTurn;
            if (active == null || !command.getTurnId().equals(active.harnessTurnId)) {
                throw new AgentOperationException("TURN_NOT_RUNNING", "Turn is not active: " + command.getTurnId());
            }
        }
        synchronized(active) {
            active.preparation.cancel();
            if (active.codexTurnId == null) {
                Thread runner=active.runner;
                if(runner!=null && !active.launching) runner.interrupt();
                return;
            }
        }
        codexGateway.interruptTurn(session.codexThreadId, active.codexTurnId);
    }

    public AgentEvent resolveApproval(ResolveApprovalCommandDTO command) {
        require(command == null ? null : command.getRequestId(), "requestId");
        if (command.getDecision() == null) {
            throw new AgentOperationException("INVALID_COMMAND", "decision must not be null");
        }
        codexGateway.resolveApproval(command.getRequestId(), command.getDecision());
        return new AgentEvent(AgentEventType.APPROVAL_RESOLVED, command.getRequestId(),
                new ApprovalResolvedEventDTO(command.getRequestId(), command.getDecision()));
    }

    public int activeTurnCount() {
        return sessions.values().stream()
                .mapToInt(session -> session.activeTurn == null ? 0 : 1).sum();
    }

    public void interruptAll() {
        acceptingTurns=false; connectionEpoch.incrementAndGet();
        for (SessionContext session : sessions.values()) {
            ActiveTurn active;
            synchronized (session) {
                active = session.activeTurn;
            }
            if(active!=null) {
                synchronized(active) {
                    canceledTurns.add(active.harnessTurnId);
                    active.preparation.cancel();
                    if(active.codexTurnId==null) {
                        if(active.runner!=null && !active.launching) active.runner.interrupt();
                        continue;
                    }
                }
            }
            if (active != null && active.codexTurnId != null) {
                AgentEventType terminalType = AgentEventType.TURN_INTERRUPTED;
                String reason = "Harness Server connection was lost";
                try {
                    codexGateway.interruptTurn(session.codexThreadId, active.codexTurnId);
                } catch (RuntimeException exception) {
                    terminalType = AgentEventType.TURN_FAILED;
                    reason = "Connection was lost and the active Turn could not be interrupted";
                }
                synchronized (active) {
                    if (finish(session, active)) {
                        codexGateway.closeThread(session.codexThreadId);
                        eventBus.publish(new AgentEvent(terminalType, active.harnessTurnId,
                                new TurnTerminalEventDTO(session.conversationId, active.harnessTurnId,
                                        active.codexTurnId, reason, active.eventSeq)));
                    }
                }
            }
        }
    }

    private CodexEventListener listener(SessionContext session, ActiveTurn active) {
        return new CodexEventListener() {
            @Override
            public void onEvent(CodexEvent event) {
                synchronized (active) {
                    if (active.finished.get()) return;
                    eventBus.publish(new AgentEvent(AgentEventType.TURN_EVENT, active.harnessTurnId,
                            new TurnEventDTO(session.conversationId, active.harnessTurnId, event.getType(),
                                    event.getItemId(), event.getContent(), event.getPhase(), event.getDetails(), ++active.eventSeq)));
                }
            }

            @Override
            public void onApproval(CodexApproval approval) {
                eventBus.publish(new AgentEvent(AgentEventType.APPROVAL_REQUIRED, approval.getRequestId(),
                        new ApprovalRequiredEventDTO(session.conversationId, active.harnessTurnId,
                                approval.getRequestId(), approval.getType(), approval.getDetails())));
            }

            @Override
            public void onCompleted(String codexTurnId, String status, String reason) {
                synchronized (active) {
                    if(active.finished.get()) return;
                    if("completed".equals(status) && !active.preparation.canceled()) {
                        try {
                            int count=artifacts.capture(session.workspace,active.harnessTurnId);
                            if(count>0) onEvent(new CodexEvent(com.myharness.agent.entity.enums.TurnEventType.ITEM_COMPLETED,
                                    "artifact-publication","已准备 "+count+" 个交付文件，正在上传；完成后可在回答下下载",null,null));
                        } catch(RuntimeException e) {
                            onEvent(new CodexEvent(com.myharness.agent.entity.enums.TurnEventType.WARNING,
                                    "artifact-publication-error",e.getMessage(),null,null));
                        }
                    }
                    if (!finish(session, active)) {
                        return;
                    }
                    AgentEventType type;
                    if ("completed".equals(status)) {
                        type = AgentEventType.TURN_COMPLETED;
                    } else if ("interrupted".equals(status)) {
                        type = AgentEventType.TURN_INTERRUPTED;
                    } else {
                        type = AgentEventType.TURN_FAILED;
                    }
                    eventBus.publish(new AgentEvent(type, active.harnessTurnId,
                            new TurnTerminalEventDTO(session.conversationId, active.harnessTurnId,
                                    codexTurnId, reason, active.eventSeq)));
                }
            }
        };
    }

    private boolean finish(SessionContext session, ActiveTurn active) {
        if (!active.finished.compareAndSet(false, true)) {
            return false;
        }
        synchronized (session) {
            if (session.activeTurn == active) {
                session.activeTurn = null;
            }
        }
        turnPermits.release();
        return true;
    }

    private SessionContext session(String conversationId) {
        SessionContext session = sessions.get(conversationId);
        if (session == null) {
            throw new AgentOperationException("CONVERSATION_NOT_STARTED",
                    "Conversation has no Codex thread: " + conversationId);
        }
        return session;
    }

    private SessionContext sessionForTurn(StartTurnCommandDTO command) {
        // Serialize creation and recovery so concurrent commands cannot rebind a project or thread.
        synchronized (isolationLock) {
            SessionContext existing = sessions.get(command.getConversationId());
            // Older Servers can continue an already loaded conversation, but cannot recover one.
            if (existing != null && command.getProjectId() == null && command.getWorkspaceName() == null
                    && command.getCodexThreadId() == null) return existing;
            require(command.getProjectId(), "projectId");
            require(command.getWorkspaceName(), "workspaceName");
            require(command.getCodexThreadId(), "codexThreadId");
            final Path workspace;
            try {
                workspace = workspaceRegistry.resolve(command.getWorkspaceName(), "").toAbsolutePath().normalize();
            } catch (WorkspaceAccessException exception) {
                throw new AgentOperationException("WORKSPACE_NOT_ALLOWED", exception.getMessage(), exception);
            }
            if (existing != null) {
                if(existing.activeTurn==null && command.getExpertRuntime()!=null
                        && command.getCodexThreadId().equals(existing.previousCodexThreadId)
                        && existing.projectId.equals(command.getProjectId()) && existing.workspace.equals(workspace)) {
                    // The Server may have rejected a replacement after cancellation; its next command is authoritative.
                    codexGateway.closeThread(existing.codexThreadId);
                    existing.codexThreadId=command.getCodexThreadId();existing.runtimeKey=command.getThreadRuntimeKey();
                    existing.previousCodexThreadId=null;
                }
                if (!existing.projectId.equals(command.getProjectId())
                        || !existing.codexThreadId.equals(command.getCodexThreadId())
                        || !existing.workspace.equals(workspace)) {
                    throw new AgentOperationException("CONVERSATION_BINDING_MISMATCH",
                            "Conversation project, workspace or Codex thread does not match its existing binding");
                }
                return existing;
            }
            if (sessions.values().stream().anyMatch(value -> value.codexThreadId.equals(command.getCodexThreadId()))) {
                throw new AgentOperationException("CONVERSATION_BINDING_MISMATCH",
                        "Codex thread is already bound to another Conversation");
            }
            reserveProjectRoot(command.getProjectId(), workspace);
            try {
                String restoredThreadId = command.getCodexThreadId();
                if(command.getExpertRuntime()!=null) {
                    SessionContext restored=new SessionContext(command.getProjectId(),command.getConversationId(),restoredThreadId,workspace);
                    restored.runtimeKey=command.getThreadRuntimeKey();restored.needsHistory=command.isRecreateUnstartedThread();
                    sessions.put(command.getConversationId(),restored);return restored;
                }
                var options = new CodexThreadOptions(command.getProjectId(), workspace, command.getModel());
                try { codexGateway.resumeThread(restoredThreadId, options); }
                catch (CodexThreadNotLoadedException missing) {
                    if (!command.isRecreateUnstartedThread()) throw missing;
                    restoredThreadId = codexGateway.startThread(options);
                    eventBus.publish(new AgentEvent(AgentEventType.THREAD_STARTED, command.getConversationId(),
                            new ThreadStartedEventDTO(command.getConversationId(), restoredThreadId,
                                    command.getCodexThreadId(), command.getTurnId())));
                }
                SessionContext restored = new SessionContext(command.getProjectId(), command.getConversationId(),
                        restoredThreadId, workspace);
                sessions.put(command.getConversationId(), restored);
                return restored;
            } catch (RuntimeException exception) {
                releaseUnusedProjectRoot(command.getProjectId(), workspace);
                throw exception;
            }
        }
    }

    private void require(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new AgentOperationException("INVALID_COMMAND", field + " must not be blank");
        }
    }

    private boolean prepareExpertThread(SessionContext session,StartTurnCommandDTO command,java.util.List<CodexSkillInput> skills,
                                         com.myharness.agent.attachment.AttachmentPreparation cancellation) {
        var runtime=command.getExpertRuntime();
        if((runtime.getSchemaVersion()!=2 && runtime.getSchemaVersion()!=3 && runtime.getSchemaVersion()!=4) || runtime.getRuntimeKey()==null || !runtime.getRuntimeKey().matches("[0-9a-f]{64}"))
            throw new AgentOperationException("EXPERT_CONFIG_INVALID","需要受支持的会话专家运行标识");
        if(runtime.getSchemaVersion()>=3 && session.expertId!=null && !session.expertId.equals(runtime.getExpertId()))
            throw new AgentOperationException("CONVERSATION_BINDING_MISMATCH","Conversation 不能切换到另一个 Expert");
        var options=new CodexThreadOptions(session.projectId,session.workspace,command.getModel())
                .withExpertRuntime(skills,runtime.getMcpServers()==null ? java.util.List.of() : runtime.getMcpServers());
        if(java.util.Objects.equals(session.runtimeKey,runtime.getRuntimeKey())) {
            try {codexGateway.resumeThread(session.codexThreadId,options);session.expertId=runtime.getExpertId();return session.needsHistory;}
            catch(CodexThreadNotLoadedException missing) {if(!command.isRecreateUnstartedThread()) throw missing;}
        }
        if(session.runtimeKey!=null && runtime.getSchemaVersion()>=3
                && runtime.isCompatibleUpgrade() && runtime.getExpertId()!=null) {
            cancellation.check();String previousKey=session.runtimeKey;
            try {
                codexGateway.resumeThread(session.codexThreadId,options);
                cancellation.check();
                eventBus.publish(new AgentEvent(AgentEventType.EXPERT_RUNTIME_UPDATED,command.getConversationId(),
                        new ExpertRuntimeUpdatedEventDTO(command.getConversationId(),command.getTurnId(),session.codexThreadId,
                                previousKey,runtime.getRuntimeKey())));
                session.runtimeKey=runtime.getRuntimeKey();session.expertId=runtime.getExpertId();session.needsHistory=false;
                return false;
            } catch(CodexThreadNotLoadedException missing) {
                if(!command.isRecreateUnstartedThread()) throw missing;
            }
        }
        cancellation.check();String previous=session.codexThreadId;
        String next=codexGateway.startThread(options);
        try {
            cancellation.check();
            eventBus.publish(new AgentEvent(AgentEventType.THREAD_STARTED,command.getConversationId(),
                    new ThreadStartedEventDTO(command.getConversationId(),next,previous,command.getTurnId())
                            .withExpertRuntimeKey(runtime.getRuntimeKey())));
        } catch(RuntimeException failure) {codexGateway.closeThread(next);throw failure;}
        session.previousCodexThreadId=previous;session.codexThreadId=next;session.runtimeKey=runtime.getRuntimeKey();session.expertId=runtime.getExpertId();
        session.needsHistory=true;
        codexGateway.closeThread(previous);
        return true;
    }

    private void reserveProjectRoot(String projectId,Path workspace) {
        synchronized (isolationLock) {
            Path assignedRoot=projectRoots.get(projectId);
            String assignedProject=rootProjects.get(workspace);
            if (assignedRoot!=null && !assignedRoot.equals(workspace))
                throw new AgentOperationException("PROJECT_ROOT_CHANGED","Project is already bound to another execution root");
            if (assignedProject!=null && !assignedProject.equals(projectId))
                throw new AgentOperationException("WORKSPACE_ALREADY_BOUND","Execution root is already bound to another project");
            projectRoots.put(projectId,workspace);
            rootProjects.put(workspace,projectId);
        }
    }

    private void releaseUnusedProjectRoot(String projectId,Path workspace) {
        synchronized (isolationLock) {
            boolean hasSession=sessions.values().stream().anyMatch(value -> projectId.equals(value.projectId));
            if (!hasSession) {
                projectRoots.remove(projectId,workspace);
                rootProjects.remove(workspace,projectId);
            }
        }
    }

    private static final class SessionContext {
        private final String projectId;
        private final String conversationId;
        private volatile String codexThreadId;
        private volatile String previousCodexThreadId;
        private volatile String runtimeKey;
        private volatile Long expertId;
        private volatile boolean needsHistory;
        private final Path workspace;
        private volatile ActiveTurn activeTurn;

        private SessionContext(String projectId,String conversationId, String codexThreadId, Path workspace) {
            this.projectId = projectId;
            this.conversationId = conversationId;
            this.codexThreadId = codexThreadId;
            this.workspace = workspace;
        }
    }

    private static final class ActiveTurn {
        private final com.myharness.agent.attachment.AttachmentPreparation preparation=new com.myharness.agent.attachment.AttachmentPreparation();
        private volatile Thread runner;
        private volatile boolean launching;
        private long eventSeq;
        private final String harnessTurnId;
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile String codexTurnId;

        private ActiveTurn(String harnessTurnId) {
            this.harnessTurnId = harnessTurnId;
        }
    }
}
