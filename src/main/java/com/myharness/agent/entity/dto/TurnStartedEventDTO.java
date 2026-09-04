package com.myharness.agent.entity.dto;

public class TurnStartedEventDTO {
    private final String conversationId;
    private final String turnId;
    private final String codexTurnId;

    public TurnStartedEventDTO(String conversationId, String turnId, String codexTurnId) {
        this.conversationId = conversationId;
        this.turnId = turnId;
        this.codexTurnId = codexTurnId;
    }

    public String getConversationId() { return conversationId; }
    public String getTurnId() { return turnId; }
    public String getCodexTurnId() { return codexTurnId; }
}
