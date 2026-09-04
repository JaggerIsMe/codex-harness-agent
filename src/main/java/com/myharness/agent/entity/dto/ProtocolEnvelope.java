package com.myharness.agent.entity.dto;

import com.fasterxml.jackson.databind.JsonNode;

public class ProtocolEnvelope {

    public static final String CURRENT_VERSION = "1.0";

    private String protocolVersion = CURRENT_VERSION;
    private String messageId;
    private String type;
    private long timestamp;
    private String deviceCode;
    private String correlationId;
    private JsonNode payload;

    public String getProtocolVersion() { return protocolVersion; }
    public void setProtocolVersion(String protocolVersion) { this.protocolVersion = protocolVersion; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public JsonNode getPayload() { return payload; }
    public void setPayload(JsonNode payload) { this.payload = payload; }
}
