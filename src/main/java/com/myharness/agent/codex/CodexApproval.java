package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.myharness.agent.entity.enums.ApprovalType;

public class CodexApproval {
    private final String requestId;
    private final ApprovalType type;
    private final JsonNode details;

    public CodexApproval(String requestId, ApprovalType type, JsonNode details) {
        this.requestId = requestId;
        this.type = type;
        this.details = details;
    }

    public String getRequestId() { return requestId; }
    public ApprovalType getType() { return type; }
    public JsonNode getDetails() { return details; }
}
