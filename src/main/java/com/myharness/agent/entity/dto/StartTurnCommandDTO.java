package com.myharness.agent.entity.dto;

public class StartTurnCommandDTO {
    private String conversationId;
    private String turnId;
    private String message;
    private String model;
    private String reasoningEffort;

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getReasoningEffort() { return reasoningEffort; }
    public void setReasoningEffort(String reasoningEffort) { this.reasoningEffort = reasoningEffort; }
}
