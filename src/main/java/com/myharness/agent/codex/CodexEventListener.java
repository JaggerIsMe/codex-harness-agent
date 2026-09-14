package com.myharness.agent.codex;

public interface CodexEventListener {
    default void onNodeOutcome(com.fasterxml.jackson.databind.JsonNode outcome) {
        throw new CodexException("Node outcome listener is not configured");
    }
    void onEvent(CodexEvent event);
    void onApproval(CodexApproval approval);
    void onCompleted(String codexTurnId, String status, String reason);
}
