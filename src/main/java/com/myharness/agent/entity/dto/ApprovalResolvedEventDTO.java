package com.myharness.agent.entity.dto;

import com.myharness.agent.entity.enums.ApprovalDecision;

public class ApprovalResolvedEventDTO {
    private final String requestId;
    private final ApprovalDecision decision;

    public ApprovalResolvedEventDTO(String requestId, ApprovalDecision decision) {
        this.requestId = requestId;
        this.decision = decision;
    }

    public String getRequestId() { return requestId; }
    public ApprovalDecision getDecision() { return decision; }
}
