package com.myharness.agent.entity.dto;

public class ThreadStartedEventDTO {
    private final String conversationId;
    private final String codexThreadId;

    public ThreadStartedEventDTO(String conversationId, String codexThreadId) {
        this.conversationId = conversationId;
        this.codexThreadId = codexThreadId;
    }

    public String getConversationId() { return conversationId; }
    public String getCodexThreadId() { return codexThreadId; }
}
