package com.myharness.agent.entity.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.myharness.agent.entity.enums.ApprovalType;

public class ApprovalRequiredEventDTO {
    private final String conversationId;
    private final String turnId;
    private final String requestId;
    private final ApprovalType approvalType;
    private final JsonNode details;

    public ApprovalRequiredEventDTO(String conversationId, String turnId, String requestId,
                                    ApprovalType approvalType, JsonNode details) {
        this.conversationId = conversationId;
        this.turnId = turnId;
        this.requestId = requestId;
        this.approvalType = approvalType;
        this.details = details;
    }

    public String getConversationId() { return conversationId; }
    public String getTurnId() { return turnId; }
    public String getRequestId() { return requestId; }
    public ApprovalType getApprovalType() { return approvalType; }
    public JsonNode getDetails() { return details; }
}
