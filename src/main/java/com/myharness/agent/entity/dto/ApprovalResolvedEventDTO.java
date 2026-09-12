package com.myharness.agent.entity.dto;

import com.myharness.agent.entity.enums.ApprovalDecision;

public class ApprovalResolvedEventDTO {
    private final String requestId;
    private final ApprovalDecision decision;
    private final String decisionMessageId;

    public ApprovalResolvedEventDTO(String requestId, ApprovalDecision decision) {
        this(requestId,decision,null);
    }
    public ApprovalResolvedEventDTO(String requestId, ApprovalDecision decision, String decisionMessageId) {
        this.requestId = requestId;
        this.decision = decision;
        this.decisionMessageId=decisionMessageId;
    }

    public String getRequestId() { return requestId; }
    public ApprovalDecision getDecision() { return decision; }
    public String getDecisionMessageId() { return decisionMessageId; }
}
