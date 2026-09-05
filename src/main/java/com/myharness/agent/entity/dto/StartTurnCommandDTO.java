package com.myharness.agent.entity.dto;

public class StartTurnCommandDTO {
    private java.util.List<TurnAttachmentDTO> attachments=java.util.List.of();
    public java.util.List<TurnAttachmentDTO> getAttachments(){return attachments==null ? java.util.List.of() : attachments;}
    public void setAttachments(java.util.List<TurnAttachmentDTO> value){attachments=value;}

    private boolean recreateUnstartedThread;
    public boolean isRecreateUnstartedThread() { return recreateUnstartedThread; }
    public void setRecreateUnstartedThread(boolean value) { recreateUnstartedThread = value; }
    private String projectId;
    private String workspaceName;
    private String codexThreadId;
    private String conversationId;
    private String turnId;
    private String message;
    private String model;
    private String reasoningEffort;

    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getWorkspaceName() { return workspaceName; }
    public void setWorkspaceName(String workspaceName) { this.workspaceName = workspaceName; }
    public String getCodexThreadId() { return codexThreadId; }
    public void setCodexThreadId(String codexThreadId) { this.codexThreadId = codexThreadId; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getTurnId() { return turnId; }
    public void setTurnId(String turnId) { this.turnId = turnId; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getReasoningEffort() { return reasoningEffort; }
    public void setReasoningEffort(String reasoningEffort) { this.reasoningEffort = reasoningEffort; }
}
