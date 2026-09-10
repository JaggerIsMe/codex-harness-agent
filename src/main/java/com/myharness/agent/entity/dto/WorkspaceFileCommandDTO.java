package com.myharness.agent.entity.dto;

public record WorkspaceFileCommandDTO(String operationId, String projectId, String workspaceName,
        String path, String cursor, long sizeBytes, String sha256, String targetPath, String expectedRevision,
        String planId, String planDigest, java.util.List<WorkspaceArchiveItemDTO> items, String requestDigest,
        WorkspaceFileLimitsDTO limits, String originalOperationId) {
    public WorkspaceFileCommandDTO(String operationId,String projectId,String workspaceName,String path,String cursor,long sizeBytes,String sha256) {
        this(operationId,projectId,workspaceName,path,cursor,sizeBytes,sha256,null,null,null,null,null,null,null,null);
    }
    public WorkspaceFileCommandDTO original() {
        return new WorkspaceFileCommandDTO(operationId,projectId,workspaceName,path,cursor,sizeBytes,sha256,targetPath,
                expectedRevision,planId,planDigest,items,requestDigest,limits,null);
    }
}
