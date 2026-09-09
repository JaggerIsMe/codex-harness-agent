package com.myharness.agent.entity.dto;

@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=true)
public record TurnAttachmentDTO(String id, String fileName, String mediaType, long sizeBytes, String sha256, String workspacePath) {}
