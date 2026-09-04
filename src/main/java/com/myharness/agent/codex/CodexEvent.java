package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.myharness.agent.entity.enums.TurnEventType;

public class CodexEvent {
    private final TurnEventType type;
    private final String itemId;
    private final String content;
    private final String phase;
    private final JsonNode details;

    public CodexEvent(TurnEventType type, String itemId, String content, JsonNode details) {
        this(type, itemId, content, null, details);
    }

    public CodexEvent(TurnEventType type, String itemId, String content, String phase, JsonNode details) {
        this.type = type;
        this.itemId = itemId;
        this.content = content;
        this.phase = phase;
        this.details = details;
    }

    public TurnEventType getType() { return type; }
    public String getItemId() { return itemId; }
    public String getContent() { return content; }
    public String getPhase() { return phase; }
    public JsonNode getDetails() { return details; }
}
