package com.myharness.agent.connection;

import com.myharness.agent.entity.enums.AgentEventType;

public class AgentEvent {
    private final AgentEventType type;
    private final String correlationId;
    private final Object payload;
    private final String messageId=java.util.UUID.randomUUID().toString();
    private final long timestamp=System.currentTimeMillis();

    public AgentEvent(AgentEventType type, String correlationId, Object payload) {
        this.type = type;
        this.correlationId = correlationId;
        this.payload = payload;
    }

    public AgentEventType getType() { return type; }
    public String getCorrelationId() { return correlationId; }
    public Object getPayload() { return payload; }
    public String getMessageId() { return messageId; }
    public long getTimestamp() { return timestamp; }
}
