package com.myharness.agent.entity.dto;

public class InterruptTurnCommandDTO {
    private String conversationId;
    private String turnId;

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }
}
