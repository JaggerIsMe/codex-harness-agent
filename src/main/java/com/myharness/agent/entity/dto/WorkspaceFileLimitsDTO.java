package com.myharness.agent.entity.dto;

public record WorkspaceFileLimitsDTO(int maxFiles, long maxFileBytes, long maxTotalBytes, long maxOutputBytes,
                                    int maxRequestBytes, int maxDurationSeconds) {}
