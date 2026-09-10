package com.myharness.agent.codex;

import com.myharness.agent.entity.enums.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationCodexGatewayTest {
    @TempDir Path workspace;
    final List<Fake> processes=new ArrayList<>();
    ConversationCodexGateway gateway() {
        return new ConversationCodexGateway(()->{var fake=new Fake("thread-"+(processes.size()+1));processes.add(fake);return fake;});
    }
    CodexThreadOptions options(String skill) {
        return new CodexThreadOptions("project",workspace,"model").withExpertSkills(
                List.of(new CodexSkillInput(skill,workspace.resolve(".harness/expert-runtimes/"+skill+"/skills/pkg/SKILL.md").toString())));
    }
    @Test void twoConversationsExecuteConcurrentlyWithSeparateProcessesAndCatalogs() {
        try(var gateway=gateway()) {
            var a=options("a");var b=options("b");
            String one=gateway.startThread(a),two=gateway.startThread(b);
            gateway.startTurn(one,new CodexTurnInput("A",null,null).withExpert("expert A",a.getExpertSkills()),mock(CodexEventListener.class));
            gateway.startTurn(two,new CodexTurnInput("B",null,null).withExpert("expert B",b.getExpertSkills()),mock(CodexEventListener.class));
            assertEquals(2,processes.size());assertEquals(a.getExpertSkills(),processes.get(0).options.getExpertSkills());
            assertEquals(b.getExpertSkills(),processes.get(1).input.getSkills());
            processes.get(0).listener.onCompleted("turn","completed",null);
            gateway.resumeThread(one,b);
            assertEquals(one,processes.get(0).resumed);
            assertEquals(b.getExpertSkills(),processes.get(0).options.getExpertSkills());
            gateway.interruptTurn(one,"turn");assertEquals("turn",processes.get(0).interrupted);assertNull(processes.get(1).interrupted);
        }
        assertTrue(processes.stream().allMatch(p->p.closed));
    }
    @Test void identicalNativeApprovalIdsAreRoutedToTheirOwningProcess() {
        try(var gateway=gateway()) {
            String one=gateway.startThread(options("a")),two=gateway.startThread(options("b"));
            var listenerA=mock(CodexEventListener.class);var listenerB=mock(CodexEventListener.class);
            gateway.startTurn(one,new CodexTurnInput("A",null,null),listenerA);
            gateway.startTurn(two,new CodexTurnInput("B",null,null),listenerB);
            processes.forEach(p->p.listener.onApproval(new CodexApproval("1",ApprovalType.COMMAND_EXECUTION,null)));
            var a=org.mockito.ArgumentCaptor.forClass(CodexApproval.class);var b=org.mockito.ArgumentCaptor.forClass(CodexApproval.class);
            verify(listenerA).onApproval(a.capture());verify(listenerB).onApproval(b.capture());
            assertNotEquals(a.getValue().getRequestId(),b.getValue().getRequestId());
            gateway.resolveApproval(b.getValue().getRequestId(),ApprovalDecision.DECLINE);
            assertEquals("1",processes.get(1).resolved);assertNull(processes.get(0).resolved);
            processes.get(0).listener.onCompleted("turn","completed",null);
            assertThrows(CodexException.class,()->gateway.resolveApproval(a.getValue().getRequestId(),ApprovalDecision.ACCEPT));
        }
    }
    @Test void idleProcessesAreReclaimedAndResumeWithTheirOriginalSkillConfiguration() {
        try(var gateway=gateway()) {
            var a=options("a");String one=gateway.startThread(a);
            gateway.startTurn(one,new CodexTurnInput("A",null,null),mock(CodexEventListener.class));
            gateway.reapIdle(System.nanoTime(),0);assertFalse(processes.get(0).closed);
            processes.get(0).listener.onCompleted("turn","completed",null);
            gateway.reapIdle(System.nanoTime(),0);assertTrue(processes.get(0).closed);
            gateway.resumeThread(one,a);assertEquals(one,processes.get(1).resumed);
            assertEquals(a.getExpertSkills(),processes.get(1).options.getExpertSkills());
        }
    }
    @Test void deadProcessIsReplacedBeforeResumingItsThread() {
        try(var gateway=gateway()) {
            var options=options("a");String id=gateway.startThread(options);processes.get(0).available=false;
            gateway.resumeThread(id,options);assertTrue(processes.get(0).closed);assertEquals(id,processes.get(1).resumed);
        }
    }
    @Test void changedMcpRuntimeReplacesOnlyTheProcessAndResumesTheSameThread() {
        try(var gateway=gateway()) {
            var first=options("a").withExpertRuntime(List.of(),List.of(mcp(1L,"a")));
            String thread=gateway.startThread(first);
            var next=options("a").withExpertRuntime(List.of(),List.of(mcp(2L,"b")));

            gateway.resumeThread(thread,next);

            assertTrue(processes.get(0).closed);assertEquals(2,processes.size());
            assertEquals(thread,processes.get(1).resumed);assertEquals("2:b:github",processes.get(1).options.getMcpRuntimeKey());
        }
    }
    private static com.myharness.agent.entity.dto.McpRuntimeDTO mcp(Long version,String digest) {
        var value=new com.myharness.agent.entity.dto.McpRuntimeDTO();value.setConfigurationVersionId(version);
        value.setConfigDigest(digest);value.setServerCode("github");return value;
    }
    @Test void failedProcessCloseRetainsTheOwnedRuntimeForAnotherStopAttempt() {
        try(var gateway=gateway()) {
            String id=gateway.startThread(options("a"));Fake process=processes.getFirst();process.failClose=true;
            assertThrows(CodexException.class,()->gateway.closeThread(id));
            gateway.interruptTurn(id,"turn");assertEquals("turn",process.interrupted);
            process.failClose=false;gateway.closeThread(id);assertTrue(process.closed);
            assertThrows(CodexException.class,()->gateway.interruptTurn(id,"turn"));
        }
    }
    static final class Fake implements CodexGateway {
        final String id;CodexThreadOptions options;CodexTurnInput input;CodexEventListener listener;
        String resolved,resumed,interrupted;boolean closed,available=true,failClose;
        Fake(String id) {this.id=id;}
        public String startThread(CodexThreadOptions options) {this.options=options;return id;}
        public void resumeThread(String id,CodexThreadOptions options) {resumed=id;this.options=options;}
        public String startTurn(String id,CodexTurnInput input,CodexEventListener listener) {this.input=input;this.listener=listener;return "turn";}
        public void interruptTurn(String id,String turn) {interrupted=turn;}
        public void resolveApproval(String id,ApprovalDecision decision) {resolved=id;}
        public boolean isAvailable() {return available;}
        public void close() {if(failClose)throw new CodexException("still executing");closed=true;}
    }
}
