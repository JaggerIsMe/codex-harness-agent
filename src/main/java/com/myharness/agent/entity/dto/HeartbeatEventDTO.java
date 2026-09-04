package com.myharness.agent.entity.dto;

public class HeartbeatEventDTO {
    private final int activeTurnCount;
    private final long uptimeSeconds;

    public HeartbeatEventDTO(int activeTurnCount, long uptimeSeconds) {
        this.activeTurnCount = activeTurnCount;
        this.uptimeSeconds = uptimeSeconds;
    }

    public int getActiveTurnCount() { return activeTurnCount; }
    public long getUptimeSeconds() { return uptimeSeconds; }
}
