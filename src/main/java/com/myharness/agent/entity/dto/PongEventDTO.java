package com.myharness.agent.entity.dto;

public class PongEventDTO {
    private final String commandMessageId;
    private final long receivedAt;

    public PongEventDTO(String commandMessageId, long receivedAt) {
        this.commandMessageId = commandMessageId;
        this.receivedAt = receivedAt;
    }

    public String getCommandMessageId() { return commandMessageId; }
    public long getReceivedAt() { return receivedAt; }
}
