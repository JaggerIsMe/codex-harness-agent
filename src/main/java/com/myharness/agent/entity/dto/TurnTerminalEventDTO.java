package com.myharness.agent.entity.dto;

public class TurnTerminalEventDTO {
    private com.fasterxml.jackson.databind.JsonNode orchestration;
    public TurnTerminalEventDTO withOrchestration(com.fasterxml.jackson.databind.JsonNode value){orchestration=value;return this;}
    public com.fasterxml.jackson.databind.JsonNode getOrchestration(){return orchestration;}
    private final String conversationId;
    private final String turnId;
    private final String codexTurnId;
    private final String reason;
    private final long lastEventSeq;

    public TurnTerminalEventDTO(String conversationId, String turnId, String codexTurnId, String reason) {
        this(conversationId,turnId,codexTurnId,reason,0);
    }
    public TurnTerminalEventDTO(String conversationId,String turnId,String codexTurnId,String reason,long lastEventSeq) {
        this.conversationId = conversationId;
        this.turnId = turnId;
        this.codexTurnId = codexTurnId;
        this.reason = reason;
        this.lastEventSeq = lastEventSeq;
    }

    public String getConversationId() { return conversationId; }
    public String getTurnId() { return turnId; }
    public String getCodexTurnId() { return codexTurnId; }
    public String getReason() { return reason; }
    public long getLastEventSeq() { return lastEventSeq; }
}
