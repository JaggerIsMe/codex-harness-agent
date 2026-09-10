package com.myharness.agent.entity.dto;

import com.myharness.agent.entity.vo.WorkspaceFileEntryVO;
import java.util.List;

public record WorkspaceFileResultDTO(String operationId, boolean success, String error,
        List<WorkspaceFileEntryVO> entries, String nextCursor, long scannedAt, long sizeBytes, String sha256,
        Integer version, String status, String outcome, String code, String sourcePath, String targetPath,
        String entryType, String entryRevision, WorkspaceDeletePlanDTO plan, WorkspaceFileSummaryDTO summary,
        List<WorkspaceFileItemResultDTO> items, String resultDigest) {
    public WorkspaceFileResultDTO(String operationId,boolean success,String error,List<WorkspaceFileEntryVO> entries,
            String nextCursor,long scannedAt,long sizeBytes,String sha256) {
        this(operationId,success,error,entries,nextCursor,scannedAt,sizeBytes,sha256,null,null,null,null,null,null,null,null,null,null,null,null);
    }
}
