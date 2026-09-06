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
    @Test void forwardsFrozenExpertAndExplicitClearOnConsecutiveTurns() {
        manager.startThread(thread("3"));
        var first=turn("3","7");var expert=new com.myharness.agent.entity.dto.ExpertRuntimeDTO();
        expert.setProjectRevision(1L);expert.setRuntimeKey("a".repeat(64));expert.setExpertVersionId(100L);expert.setSystemPrompt("Java expert");first.setExpertRuntime(expert);
        manager.startTurn(first);
        assertEquals("Java expert",gateway.lastInput.getExpertInstructions());
        var frozen=gateway.lastInput;
        gateway.listener.onCompleted("codex-turn-1","completed",null);
        var next=turn("3","8");var plain=new com.myharness.agent.entity.dto.ExpertRuntimeDTO();plain.setProjectRevision(1L);plain.setRuntimeKey("b".repeat(64));next.setExpertRuntime(plain);
        manager.startTurn(next);
        org.junit.jupiter.api.Assertions.assertTrue(gateway.lastInput.isManagedExpert());
        org.junit.jupiter.api.Assertions.assertNull(gateway.lastInput.getExpertInstructions());
        assertEquals("Java expert",frozen.getExpertInstructions());
    }
    @TempDir
    Path temporaryDirectory;

    @Test void switchesRuntimeOnlyWhenRuntimeChangesWithoutInjectingBusinessHistory() {
        manager.startThread(thread("3"));
        var first=expertTurn("7","a","codex-thread-1",null);
        manager.startTurn(first);
        assertEquals("codex-thread-2",gateway.startedTurnThreadId);
        assertEquals("do work",gateway.lastInput.getMessage());
        assertEquals("a".repeat(64),((com.myharness.agent.entity.dto.ThreadStartedEventDTO)events.getFirst().getPayload()).getExpertRuntimeKey());
        gateway.listener.onCompleted("codex-turn-1","completed",null);events.clear();
        var same=expertTurn("8","a","codex-thread-2","a");manager.startTurn(same);
        assertEquals(2,gateway.threadSequence);assertEquals("do work",gateway.lastInput.getMessage());
        gateway.listener.onCompleted("codex-turn-1","completed",null);events.clear();
        var next=expertTurn("9","b","codex-thread-2","a");next.getExpertRuntime().setExpertVersionId(200L);
        manager.startTurn(next);assertEquals("codex-thread-3",gateway.startedTurnThreadId);
        assertEquals(List.of("codex-thread-1","codex-thread-2"),gateway.closedThreads);
    }
    @Test void restartingAgentRestoresSameExpertThread() {
        var command=expertTurn("7","a","persisted-thread","a");
        manager.startTurn(command);assertEquals(0,gateway.threadSequence);assertEquals("persisted-thread",gateway.resumedThreadId);
        assertEquals("do work",gateway.lastInput.getMessage());assertEquals(0,events.size());
    }
    @Test void compatibleUpgradeKeepsTheSameExpertThreadAndPublishesTheRuntimeKey() {
        manager.startThread(thread("3"));
        manager.startTurn(expertTurn("7","a","codex-thread-1",null));
        gateway.listener.onCompleted("codex-turn-1","completed",null);events.clear();
        var next=expertTurn("8","b","codex-thread-2","a");
        next.getExpertRuntime().setExpertVersionId(101L);next.getExpertRuntime().setCompatibleUpgrade(true);
        manager.startTurn(next);
        assertEquals("codex-thread-2",gateway.startedTurnThreadId);
        assertEquals(2,gateway.threadSequence);
        assertEquals(AgentEventType.EXPERT_RUNTIME_UPDATED,events.getFirst().getType());
        var updated=(com.myharness.agent.entity.dto.ExpertRuntimeUpdatedEventDTO)events.getFirst().getPayload();
        assertEquals("a".repeat(64),updated.previousExpertRuntimeKey());
        assertEquals("b".repeat(64),updated.expertRuntimeKey());
        assertEquals(List.of("codex-thread-1"),gateway.closedThreads);
    }
    @Test void compatibilityFlagOnInitialExpertBindingStartsTheExpertThreadBeforeResumingIt() {
        manager.startThread(thread("3"));
        var first=expertTurn("7","a","codex-thread-1",null);
        first.getExpertRuntime().setCompatibleUpgrade(true);

        manager.startTurn(first);

        assertEquals(0,gateway.resumeCount);
        assertEquals(2,gateway.threadSequence);
        assertEquals("codex-thread-2",gateway.startedTurnThreadId);
        assertEquals(AgentEventType.THREAD_STARTED,events.getFirst().getType());
    }
    @Test void restartingAgentAppliesCompatibleUpgradeToThePersistedExpertThread() {
        var upgraded=expertTurn("7","b","persisted-thread","a");
        upgraded.getExpertRuntime().setCompatibleUpgrade(true);

        manager.startTurn(upgraded);

        assertEquals(0,gateway.threadSequence);
        assertEquals(1,gateway.resumeCount);
        assertEquals("persisted-thread",gateway.startedTurnThreadId);
        assertEquals(AgentEventType.EXPERT_RUNTIME_UPDATED,events.getFirst().getType());
    }
    @Test void compatibleFlagCannotSwitchAnExistingConversationToAnotherExpert() {
        manager.startThread(thread("3"));manager.startTurn(expertTurn("7","a","codex-thread-1",null));
        gateway.listener.onCompleted("codex-turn-1","completed",null);events.clear();
        var switched=expertTurn("8","b","codex-thread-2","a");
        switched.getExpertRuntime().setExpertId(20L);switched.getExpertRuntime().setCompatibleUpgrade(true);
        var failure=assertThrows(AgentOperationException.class,()->manager.startTurn(switched));
        assertEquals("CONVERSATION_BINDING_MISMATCH",failure.getErrorCode());assertEquals(2,gateway.threadSequence);
    }
    @Test void rejectedReplacementAfterCancellationCanReconcileToServerBinding() {
        manager.startThread(thread("3"));manager.startTurn(expertTurn("7","a","codex-thread-1",null));
        gateway.listener.onCompleted("codex-turn-1","completed",null);events.clear();
        // The Server did not accept the old replacement event and still sends its original binding.
        manager.startTurn(expertTurn("8","b","codex-thread-1",null));
        var replaced=(com.myharness.agent.entity.dto.ThreadStartedEventDTO)events.getFirst().getPayload();
        assertEquals("codex-thread-1",replaced.getPreviousCodexThreadId());
        assertEquals("codex-thread-3",replaced.getCodexThreadId());
    }
    private StartTurnCommandDTO expertTurn(String id,String key,String thread,String previousKey) {
        var value=turn("3",id);value.setProjectId("project-demo");value.setWorkspaceName("demo");value.setCodexThreadId(thread);
        value.setThreadRuntimeKey(previousKey==null?null:previousKey.repeat(64));
        var runtime=new com.myharness.agent.entity.dto.ExpertRuntimeDTO();runtime.setProjectRevision(1L);runtime.setExpertId(10L);
        runtime.setExpertVersionId(100L);runtime.setSystemPrompt("expert "+key);runtime.setRuntimeKey(key.repeat(64));value.setExpertRuntime(runtime);return value;
    }

    private FakeCodexGateway gateway;
    private AgentSessionManager manager;
    private List<AgentEvent> events;
    private AgentProperties properties;
    private WorkspaceRegistry registry;
    private AgentEventBus eventBus;

    private com.myharness.agent.attachment.ConversationAttachmentService attachmentService() {
        var value=org.mockito.Mockito.mock(com.myharness.agent.attachment.ConversationAttachmentService.class);
        org.mockito.Mockito.when(value.prepare(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> ((StartTurnCommandDTO)invocation.getArgument(0)).getMessage());
        return value;
    }

    private com.myharness.agent.artifact.ConversationArtifactService artifactService() {
        var value=org.mockito.Mockito.mock(com.myharness.agent.artifact.ConversationArtifactService.class);
        org.mockito.Mockito.when(value.instructions(org.mockito.ArgumentMatchers.any())).thenReturn("");
        return value;
    }

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
        manager = new AgentSessionManager(gateway, registry, eventBus, properties, attachmentService(), artifactService(), org.mockito.Mockito.mock(ExpertSkillPreparation.class));
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
        manager = new AgentSessionManager(gateway, registry, eventBus, properties, attachmentService(), artifactService(), org.mockito.Mockito.mock(ExpertSkillPreparation.class));
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

    @Test void failedAttachmentPreparationNeverStartsCodexAndReleasesTurnSlot() {
        var files=org.mockito.Mockito.mock(com.myharness.agent.attachment.ConversationAttachmentService.class);
        org.mockito.Mockito.when(files.prepare(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
                .thenThrow(new AgentOperationException("ATTACHMENT_PREPARATION_FAILED","bad checksum"));
        manager=new AgentSessionManager(gateway,registry,eventBus,properties,files,artifactService(), org.mockito.Mockito.mock(ExpertSkillPreparation.class));
        manager.startThread(thread("3"));
        var command=turn("3","7");command.setAttachments(List.of(new com.myharness.agent.entity.dto.TurnAttachmentDTO("9","a.txt","text/plain",5,"abc")));
        assertThrows(AgentOperationException.class,() -> manager.startTurn(command));
        org.junit.jupiter.api.Assertions.assertNull(gateway.startedTurnThreadId);
        assertEquals(0,manager.activeTurnCount());
        assertEquals(com.myharness.agent.entity.enums.TurnEventType.WARNING,((TurnEventDTO)events.get(0).getPayload()).getEventType());
    }

    @Test void interruptDuringPreparationNeverStartsCodex() throws Exception {
        var started=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var files=org.mockito.Mockito.mock(com.myharness.agent.attachment.ConversationAttachmentService.class);
        org.mockito.Mockito.when(files.prepare(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(i -> {
            started.countDown();
            try {release.await();} catch(InterruptedException e) {Thread.currentThread().interrupt();}
            ((com.myharness.agent.attachment.AttachmentPreparation)i.getArgument(1)).check();
            return "prepared";
        });
        manager=new AgentSessionManager(gateway,registry,eventBus,properties,files,artifactService(), org.mockito.Mockito.mock(ExpertSkillPreparation.class));manager.startThread(thread("3"));
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var result=executor.submit(() -> manager.startTurn(turn("3","7")));
            org.junit.jupiter.api.Assertions.assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS));
            var cancel=new InterruptTurnCommandDTO();cancel.setConversationId("3");cancel.setTurnId("7");manager.interruptTurn(cancel);
            release.countDown();assertEquals(AgentEventType.TURN_INTERRUPTED,result.get(5,java.util.concurrent.TimeUnit.SECONDS).getType());
            org.junit.jupiter.api.Assertions.assertNull(gateway.startedTurnThreadId);assertEquals(0,manager.activeTurnCount());
        } finally {release.countDown();executor.shutdownNow();}
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

    @Test void capturesArtifactsBeforeReleasingTurnAndOnlyOnce() {
        var artifacts=artifactService();
        var expertPreparation=org.mockito.Mockito.mock(ExpertSkillPreparation.class);
        manager=new AgentSessionManager(gateway,registry,eventBus,properties,attachmentService(),artifacts,expertPreparation);
        org.mockito.Mockito.when(artifacts.capture(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq("7")))
                .thenAnswer(i -> {assertEquals(1,manager.activeTurnCount());return 1;});
        manager.startThread(thread("3"));manager.startTurn(turn("3","7"));
        gateway.listener.onCompleted("codex-turn-1","completed",null);
        gateway.listener.onCompleted("codex-turn-1","completed",null);
        assertEquals(0,manager.activeTurnCount());
        org.mockito.Mockito.verify(artifacts).capture(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq("7"));
        
        assertEquals(AgentEventType.TURN_COMPLETED,events.getLast().getType());
    }
    @Test void failedSkillPreparationReleasesTurn() {
        var expertPreparation=org.mockito.Mockito.mock(ExpertSkillPreparation.class);
        org.mockito.Mockito.when(expertPreparation.prepare(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
                .thenThrow(new CodexException("Skill preparation failed"));
        manager=new AgentSessionManager(gateway,registry,eventBus,properties,attachmentService(),artifactService(),expertPreparation);
        manager.startThread(thread("3"));
        assertThrows(CodexException.class,()->manager.startTurn(turn("3","7")));
        assertEquals(0,manager.activeTurnCount());
    }
    @Test void captureFailureWarnsWithoutFailingCompletedTurn() {
        var artifacts=artifactService();
        manager=new AgentSessionManager(gateway,registry,eventBus,properties,attachmentService(),artifacts, org.mockito.Mockito.mock(ExpertSkillPreparation.class));
        org.mockito.Mockito.when(artifacts.capture(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
                .thenThrow(new AgentOperationException("ARTIFACT_CAPTURE_FAILED","invalid manifest"));
        manager.startThread(thread("3"));manager.startTurn(turn("3","7"));
        gateway.listener.onCompleted("codex-turn-1","completed",null);
        assertEquals(0,manager.activeTurnCount());
        assertEquals(com.myharness.agent.entity.enums.TurnEventType.WARNING,((TurnEventDTO)events.getFirst().getPayload()).getEventType());
        assertEquals(AgentEventType.TURN_COMPLETED,events.getLast().getType());
    }

    private static final class FakeCodexGateway implements CodexGateway {
        private final List<String> closedThreads=new ArrayList<>();
        @Override public void closeThread(String id) {closedThreads.add(id);}
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
            lastInput=input;
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
        private CodexTurnInput lastInput;
    }
}
