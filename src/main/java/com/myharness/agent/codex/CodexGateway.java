package com.myharness.agent.codex;

import com.myharness.agent.entity.enums.ApprovalDecision;

public interface CodexGateway extends AutoCloseable {
    String startThread(CodexThreadOptions options);
    void resumeThread(String threadId, CodexThreadOptions options);
    String startTurn(String threadId, CodexTurnInput input, CodexEventListener listener);
    void interruptTurn(String threadId, String turnId);
    void resolveApproval(String requestId, ApprovalDecision decision);
    default void resolveApproval(String requestId, ApprovalDecision decision, com.fasterxml.jackson.databind.JsonNode answers) {
        if(answers!=null&&!answers.isNull()) throw new CodexException("This gateway does not support structured answers");
        resolveApproval(requestId,decision);
    }
    default void closeThread(String threadId) { }
    default boolean isAvailable() { return true; }
    void close();
}
