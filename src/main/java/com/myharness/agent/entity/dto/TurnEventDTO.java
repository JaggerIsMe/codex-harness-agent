package com.myharness.agent.entity.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.myharness.agent.entity.enums.TurnEventType;

public class TurnEventDTO {
    private final String conversationId;
    private final String turnId;
    private final TurnEventType eventType;
    private final String itemId;
    private final String content;
    private final String phase;
    private final JsonNode details;
    private final long eventSeq;

    public TurnEventDTO(String conversationId, String turnId, TurnEventType eventType,
                        String itemId, String content, String phase, JsonNode details) {
        this(conversationId,turnId,eventType,itemId,content,phase,details,0);
    }
    public TurnEventDTO(String conversationId,String turnId,TurnEventType eventType,
                        String itemId,String content,String phase,JsonNode details,long eventSeq) {
        this.conversationId = conversationId;
        this.turnId = turnId;
        this.eventType = eventType;
        this.itemId = itemId;
        this.content = content;
        this.phase = phase;
        this.details = details;
        this.eventSeq = eventSeq;
    }

    public String getConversationId() { return conversationId; }
    public String getTurnId() { return turnId; }
    public TurnEventType getEventType() { return eventType; }
    public String getItemId() { return itemId; }
    public String getContent() { return content; }
    public String getPhase() { return phase; }
    public JsonNode getDetails() { return details; }
    public long getEventSeq() { return eventSeq; }
}
