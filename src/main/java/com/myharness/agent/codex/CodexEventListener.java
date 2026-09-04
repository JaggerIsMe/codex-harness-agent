package com.myharness.agent.codex;

public interface CodexEventListener {
    void onEvent(CodexEvent event);
    void onApproval(CodexApproval approval);
    void onCompleted(String codexTurnId, String status, String reason);
}
