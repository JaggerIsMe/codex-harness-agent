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
    private final CodexGateway codexGateway;
    private final WorkspaceRegistry workspaceRegistry;
    private final AgentEventBus eventBus;
    private final Semaphore turnPermits;
    private final Map<String, SessionContext> sessions = new ConcurrentHashMap<>();
    private final Map<String, Path> projectRoots = new ConcurrentHashMap<>();
    private final Map<Path, String> rootProjects = new ConcurrentHashMap<>();
    private final Object isolationLock = new Object();

    public AgentSessionManager(CodexGateway codexGateway, WorkspaceRegistry workspaceRegistry,
                               AgentEventBus eventBus, AgentProperties properties) {
        this.codexGateway = codexGateway;
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
            throw new AgentOperationException("CONVERSATION_ALREADY_STARTED",
                    "Conversation was started concurrently: " + command.getConversationId());
        }
        return new AgentEvent(AgentEventType.THREAD_STARTED, command.getConversationId(),
                new ThreadStartedEventDTO(command.getConversationId(), codexThreadId));
    }

    public AgentEvent startTurn(StartTurnCommandDTO command) {
        require(command == null ? null : command.getConversationId(), "conversationId");
        require(command.getTurnId(), "turnId");
        require(command.getMessage(), "message");
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
            String codexTurnId = codexGateway.startTurn(session.codexThreadId,
                    new CodexTurnInput(command.getMessage(), command.getModel(), command.getReasoningEffort()), listener);
            active.codexTurnId = codexTurnId;
            return new AgentEvent(AgentEventType.TURN_STARTED, command.getTurnId(),
                    new TurnStartedEventDTO(command.getConversationId(), command.getTurnId(), codexTurnId));
        } catch (RuntimeException exception) {
            finish(session, active);
            throw exception;
        }
    }

    public void interruptTurn(InterruptTurnCommandDTO command) {
        require(command == null ? null : command.getConversationId(), "conversationId");
        require(command.getTurnId(), "turnId");
        SessionContext session = session(command.getConversationId());
        ActiveTurn active;
        synchronized (session) {
            active = session.activeTurn;
            if (active == null || !command.getTurnId().equals(active.harnessTurnId)) {
                throw new AgentOperationException("TURN_NOT_RUNNING", "Turn is not active: " + command.getTurnId());
            }
        }
        if (active.codexTurnId == null) {
            throw new AgentOperationException("TURN_NOT_READY", "Turn has not received a Codex ID yet");
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
        for (SessionContext session : sessions.values()) {
            ActiveTurn active;
            synchronized (session) {
                active = session.activeTurn;
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
        private final String codexThreadId;
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
        private long eventSeq;
        private final String harnessTurnId;
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile String codexTurnId;

        private ActiveTurn(String harnessTurnId) {
            this.harnessTurnId = harnessTurnId;
        }
    }
}
