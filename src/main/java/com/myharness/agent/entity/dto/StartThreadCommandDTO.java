package com.myharness.agent.entity.dto;

public class StartThreadCommandDTO {
    private String projectId;
    private String conversationId;
    private String workspaceName;
    private String model;

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getWorkspaceName() { return workspaceName; }
    public void setWorkspaceName(String workspaceName) { this.workspaceName = workspaceName; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
}
