package com.myharness.agent.entity.dto;

public class WorkspaceCreateResultEventDTO {
    private final String requestId;
    private final String workspaceName;
    private final boolean success;
    private final String rootPath;
    private final String errorCode;
    private final String errorMessage;

    public WorkspaceCreateResultEventDTO(String requestId, String workspaceName, boolean success,
                                         String rootPath, String errorCode, String errorMessage) {
        this.requestId = requestId;
        this.workspaceName = workspaceName;
        this.success = success;
        this.rootPath = rootPath;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public static WorkspaceCreateResultEventDTO success(String requestId, String workspaceName, String rootPath) {
        return new WorkspaceCreateResultEventDTO(requestId, workspaceName, true, rootPath, null, null);
    }

    public static WorkspaceCreateResultEventDTO failure(String requestId, String workspaceName,
                                                        String errorCode, String errorMessage) {
        return new WorkspaceCreateResultEventDTO(requestId, workspaceName, false, null, errorCode, errorMessage);
    }

    public String getRequestId() { return requestId; }
    public String getWorkspaceName() { return workspaceName; }
    public boolean isSuccess() { return success; }
    public String getRootPath() { return rootPath; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
}
