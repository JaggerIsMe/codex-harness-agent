package com.myharness.agent.entity.dto;

import com.myharness.agent.entity.vo.WorkspaceFileEntryVO;
import java.util.List;

public record WorkspaceFileResultDTO(String operationId, boolean success, String error,
        List<WorkspaceFileEntryVO> entries, String nextCursor, long scannedAt, long sizeBytes, String sha256) {}
