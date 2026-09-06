package com.myharness.agent.entity.dto;

public record ExpertRuntimeUpdatedEventDTO(String conversationId, String turnId, String codexThreadId,
                                           String previousExpertRuntimeKey, String expertRuntimeKey) {}
