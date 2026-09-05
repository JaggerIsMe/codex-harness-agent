package com.myharness.agent.entity.dto;

/** Persisted only in the Agent data directory, outside Codex's Workspace. */
public record ArtifactJobDTO(String turnId,String artifactKey,String fileName,long sizeBytes,String sha256) {}
