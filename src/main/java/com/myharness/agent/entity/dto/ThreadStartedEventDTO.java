package com.myharness.agent.entity.dto;

public class ThreadStartedEventDTO {
    private final String conversationId;
    private final String codexThreadId;
    private final String previousCodexThreadId;
    private final String turnId;

    public ThreadStartedEventDTO(String conversationId, String codexThreadId) {
        this(conversationId, codexThreadId, null, null);
    }
    public ThreadStartedEventDTO(String conversationId, String codexThreadId, String previousCodexThreadId, String turnId) {
        this.conversationId = conversationId;
        this.codexThreadId = codexThreadId;
        this.previousCodexThreadId = previousCodexThreadId;
        this.turnId = turnId;
    }

    public String getConversationId() { return conversationId; }
    public String getCodexThreadId() { return codexThreadId; }
    public String getPreviousCodexThreadId() { return previousCodexThreadId; }
    public String getTurnId() { return turnId; }
}
