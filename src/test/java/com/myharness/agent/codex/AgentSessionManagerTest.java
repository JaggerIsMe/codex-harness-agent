package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.agent.command.AgentOperationException;
import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.config.WorkspaceProperties;
import com.myharness.agent.connection.AgentEvent;
import com.myharness.agent.connection.AgentEventBus;
import com.myharness.agent.entity.dto.InterruptTurnCommandDTO;
import com.myharness.agent.entity.dto.StartThreadCommandDTO;
import com.myharness.agent.entity.dto.StartTurnCommandDTO;
import com.myharness.agent.entity.dto.TurnEventDTO;
import com.myharness.agent.entity.enums.AgentEventType;
import com.myharness.agent.entity.enums.ApprovalDecision;
import com.myharness.agent.workspace.WorkspaceRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentSessionManagerTest {
    @TempDir
    Path temporaryDirectory;

    private FakeCodexGateway gateway;
    private AgentSessionManager manager;
    private List<AgentEvent> events;

    @BeforeEach
    void setUp() throws Exception {
        Path workspaceRoot = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        AgentProperties properties = new AgentProperties();
        properties.setDataDir(temporaryDirectory.resolve("agent-data"));
        properties.setMaxConcurrentTurns(1);
        WorkspaceProperties workspace = new WorkspaceProperties();
        workspace.setName("demo");
        workspace.setPath(workspaceRoot);
        properties.setWorkspaces(Collections.singletonList(workspace));
        AgentEventBus eventBus = new AgentEventBus();
        events = new ArrayList<>();
        eventBus.subscribe(events::add);
        gateway = new FakeCodexGateway();
        manager = new AgentSessionManager(gateway, new WorkspaceRegistry(properties, new ObjectMapper()), eventBus, properties);
    }

    @Test
    void shouldStartThreadAndTurnThenPublishCompletion() {
        AgentEvent thread = manager.startThread(thread("conversation-1"));
        AgentEvent turn = manager.startTurn(turn("conversation-1", "turn-1"));

        assertEquals(AgentEventType.THREAD_STARTED, thread.getType());
        assertEquals(AgentEventType.TURN_STARTED, turn.getType());
        assertEquals(1, manager.activeTurnCount());

        gateway.listener.onEvent(new CodexEvent(com.myharness.agent.entity.enums.TurnEventType.AGENT_MESSAGE_DELTA,
                "item-1", "hello", "commentary", null));
        gateway.listener.onCompleted("codex-turn-1", "completed", null);

        assertEquals(0, manager.activeTurnCount());
        assertEquals(AgentEventType.TURN_EVENT, events.get(0).getType());
        assertEquals("commentary", ((TurnEventDTO) events.get(0).getPayload()).getPhase());
        assertEquals(1, ((TurnEventDTO) events.get(0).getPayload()).getEventSeq());
        assertEquals(1, ((com.myharness.agent.entity.dto.TurnTerminalEventDTO) events.get(1).getPayload()).getLastEventSeq());
        assertEquals(AgentEventType.TURN_COMPLETED, events.get(1).getType());
        gateway.listener.onEvent(new CodexEvent(com.myharness.agent.entity.enums.TurnEventType.AGENT_MESSAGE_DELTA,"item-1","late",null));
        gateway.listener.onCompleted("codex-turn-1","completed",null);
        assertEquals(2,events.size());
    }

    @Test
    void shouldEnforceGlobalConcurrentTurnLimitAndInterruptMatchingTurn() {
        manager.startThread(thread("conversation-1"));
        manager.startThread(thread("conversation-2"));
        manager.startTurn(turn("conversation-1", "turn-1"));

        AgentOperationException busy = assertThrows(AgentOperationException.class,
                () -> manager.startTurn(turn("conversation-2", "turn-2")));
        assertEquals("AGENT_BUSY", busy.getErrorCode());

        InterruptTurnCommandDTO interrupt = new InterruptTurnCommandDTO();
        interrupt.setConversationId("conversation-1");
        interrupt.setTurnId("turn-1");
        manager.interruptTurn(interrupt);

        assertEquals("codex-thread-1", gateway.interruptedThreadId);
        assertEquals("codex-turn-1", gateway.interruptedTurnId);
    }

    @Test
    void shouldRejectDifferentProjectsSharingTheSameExecutionRoot() {
        manager.startThread(thread("conversation-1"));
        StartThreadCommandDTO command=thread("conversation-2");
        command.setProjectId("another-project");

        AgentOperationException exception=assertThrows(AgentOperationException.class,() -> manager.startThread(command));

        assertEquals("WORKSPACE_ALREADY_BOUND",exception.getErrorCode());
    }

    private StartThreadCommandDTO thread(String conversationId) {
        StartThreadCommandDTO command = new StartThreadCommandDTO();
        command.setProjectId("project-demo");
        command.setConversationId(conversationId);
        command.setWorkspaceName("demo");
        return command;
    }

    private StartTurnCommandDTO turn(String conversationId, String turnId) {
        StartTurnCommandDTO command = new StartTurnCommandDTO();
        command.setConversationId(conversationId);
        command.setTurnId(turnId);
        command.setMessage("do work");
        return command;
    }

    private static final class FakeCodexGateway implements CodexGateway {
        private int threadSequence;
        private CodexEventListener listener;
        private String interruptedThreadId;
        private String interruptedTurnId;

        @Override
        public String startThread(CodexThreadOptions options) {
            threadSequence++;
            return "codex-thread-" + threadSequence;
        }

        @Override
        public String startTurn(String threadId, CodexTurnInput input, CodexEventListener listener) {
            this.listener = listener;
            return "codex-turn-1";
        }

        @Override
        public void interruptTurn(String threadId, String turnId) {
            interruptedThreadId = threadId;
            interruptedTurnId = turnId;
        }

        @Override
        public void resolveApproval(String requestId, ApprovalDecision decision) {
        }

        @Override
        public void close() {
        }
    }
}
