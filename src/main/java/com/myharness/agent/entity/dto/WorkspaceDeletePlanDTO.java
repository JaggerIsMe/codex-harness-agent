package com.myharness.agent.entity.dto;

public record WorkspaceDeletePlanDTO(String planId, String planDigest, String path, String entryType,
        String entryRevision, long fileCount, long directoryCount, long totalBytes, long expiresAt) {}
