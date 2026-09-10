package com.myharness.agent.entity.dto;

import com.myharness.agent.entity.vo.WorkspaceVO;
import com.myharness.agent.entity.vo.WorkspaceRootVO;
import java.util.List;

public class RegisterEventDTO {
    private final String deviceName;
    private final String agentVersion;
    private final String osName;
    private final String osVersion;
    private final String isolationMode;
    private final List<WorkspaceVO> workspaces;
    private final List<WorkspaceRootVO> workspaceRoots;
    private WorkspaceFileLimitsDTO workspaceFileLimits;
    public WorkspaceFileLimitsDTO getWorkspaceFileLimits(){return workspaceFileLimits;}
    public RegisterEventDTO withWorkspaceFileLimits(WorkspaceFileLimitsDTO value){workspaceFileLimits=value;return this;}

    public RegisterEventDTO(String deviceName, String agentVersion, String osName, String osVersion,String isolationMode,
                            List<WorkspaceVO> workspaces, List<WorkspaceRootVO> workspaceRoots) {
        this.deviceName = deviceName;
        this.agentVersion = agentVersion;
        this.osName = osName;
        this.osVersion = osVersion;
        this.isolationMode = isolationMode;
        this.workspaces = workspaces;
        this.workspaceRoots = workspaceRoots;
    }

    public java.util.List<String> getCapabilities(){
        var values=new java.util.ArrayList<>(java.util.List.of("WORKSPACE_FILES_V1","WORKSPACE_ARCHIVE_DOWNLOAD_V1","CONVERSATION_ATTACHMENTS_V1","CONVERSATION_EXPERTS_V4","MANAGED_MODEL_PROVIDERS_V1","MODEL_RUNTIME_TARGETS_V2"));
        if(com.sun.jna.Platform.isWindows())values.add("WORKSPACE_FILE_MUTATIONS_V1");
        return java.util.List.copyOf(values);
    }
    public String getDeviceName() { return deviceName; }
    public String getAgentVersion() { return agentVersion; }
    public String getOsName() { return osName; }
    public String getOsVersion() { return osVersion; }
    public String getIsolationMode() { return isolationMode; }
    public List<WorkspaceVO> getWorkspaces() { return workspaces; }
    public List<WorkspaceRootVO> getWorkspaceRoots() { return workspaceRoots; }
}
