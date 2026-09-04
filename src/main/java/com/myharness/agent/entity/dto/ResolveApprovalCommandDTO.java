package com.myharness.agent.entity.dto;

import com.myharness.agent.entity.enums.ApprovalDecision;

public class ResolveApprovalCommandDTO {
    private String requestId;
    private ApprovalDecision decision;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public ApprovalDecision getDecision() { return decision; }
    public void setDecision(ApprovalDecision decision) { this.decision = decision; }
}
