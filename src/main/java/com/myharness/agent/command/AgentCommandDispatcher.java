package com.myharness.agent.command;

import com.myharness.agent.codex.AgentSessionManager;
import com.myharness.agent.connection.AgentEvent;
import com.myharness.agent.connection.AgentEventBus;
import com.myharness.agent.connection.ProtocolCodec;
import com.myharness.agent.entity.dto.ErrorEventDTO;
import com.myharness.agent.entity.dto.CreateWorkspaceCommandDTO;
import com.myharness.agent.entity.dto.InstallSkillCommandDTO;
import com.myharness.agent.entity.dto.InterruptTurnCommandDTO;
import com.myharness.agent.entity.dto.PongEventDTO;
import com.myharness.agent.entity.dto.ProtocolEnvelope;
import com.myharness.agent.entity.dto.RemoveSkillCommandDTO;
import com.myharness.agent.entity.dto.ResolveApprovalCommandDTO;
import com.myharness.agent.entity.dto.SkillResultEventDTO;
import com.myharness.agent.entity.dto.StartThreadCommandDTO;
import com.myharness.agent.entity.dto.StartTurnCommandDTO;
import com.myharness.agent.entity.dto.WorkspaceCreateResultEventDTO;
import com.myharness.agent.entity.enums.AgentCommandType;
import com.myharness.agent.entity.enums.AgentEventType;
import com.myharness.agent.skill.SkillException;
import com.myharness.agent.skill.SkillInstallationService;
import com.myharness.agent.workspace.WorkspaceRegistry;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class AgentCommandDispatcher {
    private final ProtocolCodec codec;
    private final CommandDeduplicator deduplicator;
    private final AgentSessionManager sessionManager;
    private final SkillInstallationService skillService;
    private final WorkspaceRegistry workspaceRegistry;
    private final AgentEventBus eventBus;

    public AgentCommandDispatcher(ProtocolCodec codec, CommandDeduplicator deduplicator,
                                  AgentSessionManager sessionManager, SkillInstallationService skillService,
                                  WorkspaceRegistry workspaceRegistry, AgentEventBus eventBus) {
        this.codec = codec;
        this.deduplicator = deduplicator;
        this.sessionManager = sessionManager;
        this.skillService = skillService;
        this.workspaceRegistry = workspaceRegistry;
        this.eventBus = eventBus;
    }

    public void handle(ProtocolEnvelope envelope) {
        List<AgentEvent> results = deduplicator.execute(envelope.getMessageId(), () -> execute(envelope));
        for (AgentEvent result : results) {
            eventBus.publish(result);
        }
    }

    private List<AgentEvent> execute(ProtocolEnvelope envelope) {
        AgentCommandType type = AgentCommandType.valueOf(envelope.getType());
        try {
            switch (type) {
                case START_THREAD:
                    return one(sessionManager.startThread(codec.payload(envelope, StartThreadCommandDTO.class)));
                case START_TURN:
                    return one(sessionManager.startTurn(codec.payload(envelope, StartTurnCommandDTO.class)));
                case INTERRUPT_TURN:
                    sessionManager.interruptTurn(codec.payload(envelope, InterruptTurnCommandDTO.class));
                    return Collections.emptyList();
                case RESOLVE_APPROVAL:
                    return one(sessionManager.resolveApproval(codec.payload(envelope, ResolveApprovalCommandDTO.class)));
                case INSTALL_SKILL:
                    return install(envelope);
                case REMOVE_SKILL:
                    return remove(envelope);
                case CREATE_WORKSPACE:
                    return createWorkspace(envelope);
                case REFRESH_WORKSPACES:
                    return one(new AgentEvent(AgentEventType.WORKSPACES_CHANGED, envelope.getCorrelationId(),
                            workspaceRegistry.list()));
                case PING:
                    return one(new AgentEvent(AgentEventType.PONG, envelope.getCorrelationId(),
                            new PongEventDTO(envelope.getMessageId(), System.currentTimeMillis())));
                default:
                    throw new AgentOperationException("UNSUPPORTED_COMMAND", "Unsupported command: " + type);
            }
        } catch (AgentOperationException exception) {
            return error(envelope, exception.getErrorCode(), exception.getMessage());
        } catch (RuntimeException exception) {
            return error(envelope, "COMMAND_FAILED", safeMessage(exception));
        }
    }

    private List<AgentEvent> install(ProtocolEnvelope envelope) {
        InstallSkillCommandDTO command = codec.payload(envelope, InstallSkillCommandDTO.class);
        try {
            SkillResultEventDTO result = skillService.install(command);
            return one(new AgentEvent(AgentEventType.SKILL_INSTALL_RESULT, envelope.getCorrelationId(), result));
        } catch (SkillException exception) {
            SkillResultEventDTO result = new SkillResultEventDTO(command.getSkillId(), command.getVersion(),
                    false, null, exception.getMessage());
            return one(new AgentEvent(AgentEventType.SKILL_INSTALL_RESULT, envelope.getCorrelationId(), result));
        }
    }

    private List<AgentEvent> remove(ProtocolEnvelope envelope) {
        RemoveSkillCommandDTO command = codec.payload(envelope, RemoveSkillCommandDTO.class);
        try {
            SkillResultEventDTO result = skillService.remove(command);
            return one(new AgentEvent(AgentEventType.SKILL_REMOVE_RESULT, envelope.getCorrelationId(), result));
        } catch (SkillException exception) {
            SkillResultEventDTO result = new SkillResultEventDTO(command.getSkillId(), command.getVersion(),
                    false, null, exception.getMessage());
            return one(new AgentEvent(AgentEventType.SKILL_REMOVE_RESULT, envelope.getCorrelationId(), result));
        }
    }

    private List<AgentEvent> createWorkspace(ProtocolEnvelope envelope) {
        CreateWorkspaceCommandDTO command = codec.payload(envelope, CreateWorkspaceCommandDTO.class);
        WorkspaceCreateResultEventDTO result = workspaceRegistry.create(command);
        List<AgentEvent> events = new java.util.ArrayList<>();
        events.add(new AgentEvent(AgentEventType.WORKSPACE_CREATE_RESULT, envelope.getCorrelationId(), result));
        if (result.isSuccess()) {
            events.add(new AgentEvent(AgentEventType.WORKSPACES_CHANGED, envelope.getCorrelationId(),
                    workspaceRegistry.list()));
        }
        return events;
    }

    private List<AgentEvent> error(ProtocolEnvelope envelope, String code, String message) {
        ErrorEventDTO payload = new ErrorEventDTO(code, message, envelope.getType(), envelope.getMessageId());
        return one(new AgentEvent(AgentEventType.ERROR, envelope.getCorrelationId(), payload));
    }

    private List<AgentEvent> one(AgentEvent event) {
        return Collections.singletonList(event);
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.trim().isEmpty() ? exception.getClass().getSimpleName() : message;
    }
}
