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
    private AgentProperties properties;
    private WorkspaceRegistry registry;
    private AgentEventBus eventBus;

    @BeforeEach
    void setUp() throws Exception {
        Path workspaceRoot = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        properties = new AgentProperties();
        properties.setDataDir(temporaryDirectory.resolve("agent-data"));
        properties.setMaxConcurrentTurns(1);
        WorkspaceProperties workspace = new WorkspaceProperties();
        workspace.setName("demo");
        workspace.setPath(workspaceRoot);
        properties.setWorkspaces(Collections.singletonList(workspace));
        eventBus = new AgentEventBus();
        events = new ArrayList<>();
        eventBus.subscribe(events::add);
        gateway = new FakeCodexGateway();
        registry = new WorkspaceRegistry(properties, new ObjectMapper());
        manager = new AgentSessionManager(gateway, registry, eventBus, properties);
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

    @Test
    void resumesOriginalConversationAfterAgentRestartAndContinuesStreaming() {
        manager.startThread(thread("2"));
        manager.startTurn(turn("2", "2"));
        gateway.listener.onCompleted("codex-turn-1", "completed", null);
        manager = new AgentSessionManager(gateway, registry, eventBus, properties);
        events.clear();

        StartTurnCommandDTO followUp = recoverableTurn("2", "7");
        AgentEvent started = manager.startTurn(followUp);

        assertEquals(AgentEventType.TURN_STARTED, started.getType());
        assertEquals("codex-thread-1", gateway.resumedThreadId);
        assertEquals("codex-thread-1", gateway.startedTurnThreadId);
        assertEquals(1, gateway.threadSequence, "Recovery must not create a replacement thread");
        gateway.listener.onEvent(new CodexEvent(com.myharness.agent.entity.enums.TurnEventType.AGENT_MESSAGE_DELTA,
                "answer", "hello again", null));
        gateway.listener.onCompleted("codex-turn-1", "completed", null);
        assertEquals(AgentEventType.TURN_EVENT, events.get(0).getType());
        assertEquals(AgentEventType.TURN_COMPLETED, events.get(1).getType());
        assertEquals(0, manager.activeTurnCount());
        manager.startTurn(recoverableTurn("2", "8"));
        assertEquals(1, gateway.resumeCount, "Warm conversations must reuse the restored mapping");
    }

    @Test
    void failedRecoveryReleasesCapacityAndDoesNotCacheSessionOrProjectBinding() {
        gateway.failResume = true;
        assertThrows(CodexException.class, () -> manager.startTurn(recoverableTurn("2", "7")));
        assertEquals(0, manager.activeTurnCount());
        gateway.failResume = false;
        StartTurnCommandDTO retry = recoverableTurn("2", "8");
        retry.setProjectId("another-project");
        manager.startTurn(retry);
        assertEquals(2, gateway.resumeCount);
        assertEquals(1, manager.activeTurnCount());
    }
    @Test void recreatesOnlyExplicitlyAuthorizedUnstartedThreadAndPublishesNewBinding() {
        gateway.missingThread=true;
        var command=recoverableTurn("2","7");
        command.setCodexThreadId("missing-thread");
        assertThrows(CodexThreadNotLoadedException.class,()->manager.startTurn(command));
        assertEquals(0,gateway.threadSequence);
        command.setRecreateUnstartedThread(true);
        manager.startTurn(command);
        assertEquals(1,gateway.threadSequence);
        assertEquals(AgentEventType.THREAD_STARTED,events.get(0).getType());
        var replacement=(com.myharness.agent.entity.dto.ThreadStartedEventDTO)events.get(0).getPayload();
        assertEquals("missing-thread",replacement.getPreviousCodexThreadId());
        assertEquals("7",replacement.getTurnId());
        assertEquals(gateway.startedTurnThreadId,replacement.getCodexThreadId());
    }
    @Test void recreationAuthorizationDoesNotHideOtherResumeFailures() {
        gateway.failResume=true;
        var command=recoverableTurn("2","7");command.setRecreateUnstartedThread(true);
        assertThrows(CodexException.class,()->manager.startTurn(command));
        assertEquals(0,gateway.threadSequence);assertEquals(0,events.size());
    }

    @Test
    void recoveryCannotReuseAnotherProjectsWorkspaceOrChangeAnExistingConversation() {
        manager.startThread(thread("existing"));
        StartTurnCommandDTO otherProject = recoverableTurn("2", "7");
        otherProject.setProjectId("another-project");
        otherProject.setCodexThreadId("another-thread");
        assertEquals("WORKSPACE_ALREADY_BOUND", assertThrows(AgentOperationException.class,
                () -> manager.startTurn(otherProject)).getErrorCode());
        StartTurnCommandDTO changedThread = recoverableTurn("existing", "8");
        changedThread.setCodexThreadId("different-thread");
        assertEquals("CONVERSATION_BINDING_MISMATCH", assertThrows(AgentOperationException.class,
                () -> manager.startTurn(changedThread)).getErrorCode());
        assertEquals("CONVERSATION_BINDING_MISMATCH", assertThrows(AgentOperationException.class,
                () -> manager.startTurn(recoverableTurn("duplicate-conversation", "9"))).getErrorCode());
        assertEquals(0, gateway.resumeCount);
    }

    private StartTurnCommandDTO recoverableTurn(String conversationId, String turnId) {
        StartTurnCommandDTO command = turn(conversationId, turnId);
        command.setProjectId("project-demo");
        command.setWorkspaceName("demo");
        command.setCodexThreadId("codex-thread-1");
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
        private String resumedThreadId;
        private String startedTurnThreadId;
        private int resumeCount;
        private boolean failResume;
        private boolean missingThread;

        @Override
        public void resumeThread(String threadId, CodexThreadOptions options) {
            resumeCount++;
            if (failResume) throw new CodexException("Stored thread unavailable");
            if (missingThread) throw new CodexThreadNotLoadedException(threadId,null);
            resumedThreadId = threadId;
        }

        @Override
        public String startThread(CodexThreadOptions options) {
            threadSequence++;
            return "codex-thread-" + threadSequence;
        }

        @Override
        public String startTurn(String threadId, CodexTurnInput input, CodexEventListener listener) {
            startedTurnThreadId = threadId;
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
