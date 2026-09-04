package com.myharness.agent.workspace;

public class DynamicWorkspaceRecord {
    private String requestId;
    private String parentName;
    private String workspaceName;
    private String projectType;
    private String state;
    private String temporaryDirectoryName;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getParentName() { return parentName; }
    public void setParentName(String parentName) { this.parentName = parentName; }
    public String getWorkspaceName() { return workspaceName; }
    public void setWorkspaceName(String workspaceName) { this.workspaceName = workspaceName; }
    public String getProjectType() { return projectType; }
    public void setProjectType(String projectType) { this.projectType = projectType; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getTemporaryDirectoryName() { return temporaryDirectoryName; }
    public void setTemporaryDirectoryName(String temporaryDirectoryName) { this.temporaryDirectoryName = temporaryDirectoryName; }
}
