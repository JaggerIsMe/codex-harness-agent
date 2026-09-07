package com.myharness.agent.entity.dto;

public record WorkspaceFileCommandDTO(String operationId, String projectId, String workspaceName,
        String path, String cursor, long sizeBytes, String sha256) {}
