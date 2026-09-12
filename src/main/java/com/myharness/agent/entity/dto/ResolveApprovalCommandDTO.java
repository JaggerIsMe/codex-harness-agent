package com.myharness.agent.entity.dto;

import com.myharness.agent.entity.enums.ApprovalDecision;

public class ResolveApprovalCommandDTO {
    private com.fasterxml.jackson.databind.JsonNode answers;
    private String decisionMessageId;
    public com.fasterxml.jackson.databind.JsonNode getAnswers() { return answers; }
    public void setAnswers(com.fasterxml.jackson.databind.JsonNode value) { answers=value; }
    public String getDecisionMessageId() { return decisionMessageId; }
    public void setDecisionMessageId(String value) { decisionMessageId=value; }
    private String requestId;
    private ApprovalDecision decision;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public ApprovalDecision getDecision() { return decision; }
    public void setDecision(ApprovalDecision decision) { this.decision = decision; }
}
