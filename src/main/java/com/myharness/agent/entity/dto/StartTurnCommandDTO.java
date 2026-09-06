package com.myharness.agent.entity.dto;

public class StartTurnCommandDTO {
    private String threadModelRuntimeKey;
    public String getThreadModelRuntimeKey(){return threadModelRuntimeKey;}
    public void setThreadModelRuntimeKey(String value){threadModelRuntimeKey=value;}
    private ModelRuntimeDTO modelRuntime;
    public ModelRuntimeDTO getModelRuntime(){return modelRuntime;}
    public void setModelRuntime(ModelRuntimeDTO value){modelRuntime=value;}
    private String threadRuntimeKey;
    public String getThreadRuntimeKey() { return threadRuntimeKey; }
    public void setThreadRuntimeKey(String value) { threadRuntimeKey=value; }
    private ExpertRuntimeDTO expertRuntime;
    public ExpertRuntimeDTO getExpertRuntime() {return expertRuntime;}
    public void setExpertRuntime(ExpertRuntimeDTO value) {expertRuntime=value;}
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
}
