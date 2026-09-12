package com.myharness.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "harness.agent")
public class AgentProperties {
    @Min(1) private int workspaceArchiveMaxFiles=100;
    @Min(1) private long workspaceArchiveMaxTotalBytes=100L*1024*1024;
    @Min(1) private long workspaceArchiveMaxOutputBytes=110L*1024*1024;
    public int getWorkspaceArchiveMaxFiles(){return workspaceArchiveMaxFiles;}
    public void setWorkspaceArchiveMaxFiles(int value){workspaceArchiveMaxFiles=value;}
    public long getWorkspaceArchiveMaxTotalBytes(){return workspaceArchiveMaxTotalBytes;}
    public void setWorkspaceArchiveMaxTotalBytes(long value){workspaceArchiveMaxTotalBytes=value;}
    public long getWorkspaceArchiveMaxOutputBytes(){return workspaceArchiveMaxOutputBytes;}
    public void setWorkspaceArchiveMaxOutputBytes(long value){workspaceArchiveMaxOutputBytes=value;}
    private boolean responsesHistoryCompatibility;
    public boolean isResponsesHistoryCompatibility(){return responsesHistoryCompatibility;}
    public void setResponsesHistoryCompatibility(boolean value){responsesHistoryCompatibility=value;}
    private long maxAttachmentBytes=20L*1024*1024;
    private long maxTurnAttachmentBytes=50L*1024*1024;
    public long getMaxAttachmentBytes(){return maxAttachmentBytes;}
    public void setMaxAttachmentBytes(long value){maxAttachmentBytes=value;}
    public long getMaxTurnAttachmentBytes(){return maxTurnAttachmentBytes;}
    public void setMaxTurnAttachmentBytes(long value){maxTurnAttachmentBytes=value;}


    @NotNull
    private URI serverUrl;

    private String deviceCode;

    private String deviceToken;

    private URI enrollmentUrl;

    private String enrollmentCode;

    private String deviceName;

    @NotNull
    private Path dataDir;


    @Min(1)
    private int maxConcurrentTurns = 1;

    private String codexCommand = "codex";

    private boolean strictProjectIsolation = true;

    @Min(0) private int maxWorkspaces;
    public int getMaxWorkspaces() { return maxWorkspaces; }
    public void setMaxWorkspaces(int value) { maxWorkspaces=value; }
    private volatile boolean readIsolationVerified;
    public void confirmReadIsolation() { readIsolationVerified=true; }
    private Path windowsPython;
    public Path getWindowsPython() {return windowsPython;}
    public void setWindowsPython(Path value) {windowsPython=value;}
    @Valid private java.util.Map<String,WindowsTool> windowsTools=new java.util.LinkedHashMap<>();
    public java.util.Map<String,WindowsTool> getWindowsTools(){return windowsTools;}
    public void setWindowsTools(java.util.Map<String,WindowsTool> value){windowsTools=value==null?new java.util.LinkedHashMap<>():value;}
    public static class WindowsTool {
        @NotNull private Path home;
        @NotBlank private String executable;
        public Path getHome(){return home;} public void setHome(Path value){home=value;}
        public String getExecutable(){return executable;} public void setExecutable(String value){executable=value;}
    }

    public String isolationMode(String osName) {
        if(!strictProjectIsolation || !readIsolationVerified) return "UNSUPPORTED";
        if("Linux".equalsIgnoreCase(osName)) return "LINUX_PROJECT_PROFILE_V1";
        if(osName!=null && osName.startsWith("Windows")) return "WINDOWS_LPAC_V1";
        return "UNSUPPORTED";
    }

    @NotBlank @Pattern(regexp="(?i)elevated")
    private String windowsSandbox = "elevated";

    @Min(1)
    private long heartbeatIntervalSeconds = 15L;

    @Min(1)
    private long reconnectMaxDelaySeconds = 30L;

    @Min(1)
    private int commandDeduplicationSize = 1000;

    @Min(1)
    private long codexRequestTimeoutSeconds = 30L;

    @Min(1)
    private long skillMaxDownloadSizeMb = 20L;

    @Min(1)
    private int skillMaxFileCount = 500;

    @Min(1)
    private long skillMaxSingleFileSizeMb = 20L;

    @Min(1)
    private long skillMaxExpandedSizeMb = 100L;

    @Valid
    @NotNull
    private List<WorkspaceProperties> workspaces = new ArrayList<>();

    @Valid
    @NotNull
    private List<WorkspaceRootProperties> workspaceRoots = new ArrayList<>();

    public URI getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(URI serverUrl) {
        this.serverUrl = serverUrl;
    }

    public String getDeviceCode() {
        return deviceCode;
    }

    public void setDeviceCode(String deviceCode) {
        this.deviceCode = deviceCode;
    }

    public String getDeviceToken() {
        return deviceToken;
    }

    public URI getEnrollmentUrl() {
        return enrollmentUrl;
    }

    public void setEnrollmentUrl(URI enrollmentUrl) {
        this.enrollmentUrl = enrollmentUrl;
    }

    public String getEnrollmentCode() {
        return enrollmentCode;
    }

    public void setEnrollmentCode(String enrollmentCode) {
        this.enrollmentCode = enrollmentCode;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public void setDeviceName(String deviceName) {
        this.deviceName = deviceName;
    }

    public void setDeviceToken(String deviceToken) {
        this.deviceToken = deviceToken;
    }

    public Path getDataDir() {
        return dataDir;
    }

    public void setDataDir(Path dataDir) {
        this.dataDir = dataDir;
    }

    public int getMaxConcurrentTurns() {
        return maxConcurrentTurns;
    }

    public void setMaxConcurrentTurns(int maxConcurrentTurns) {
        this.maxConcurrentTurns = maxConcurrentTurns;
    }

    public String getCodexCommand() {
        return codexCommand;
    }

    public void setCodexCommand(String codexCommand) {
        this.codexCommand = codexCommand;
    }

    @AssertTrue(message="strict project isolation must remain enabled")
    public boolean isStrictProjectIsolation() { return strictProjectIsolation; }
    public void setStrictProjectIsolation(boolean value) { strictProjectIsolation=value; }
    public String getWindowsSandbox() { return windowsSandbox; }
    public void setWindowsSandbox(String value) { windowsSandbox=value; }

    public long getHeartbeatIntervalSeconds() {
        return heartbeatIntervalSeconds;
    }

    public void setHeartbeatIntervalSeconds(long heartbeatIntervalSeconds) {
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
    }

    public long getReconnectMaxDelaySeconds() {
        return reconnectMaxDelaySeconds;
    }

    public void setReconnectMaxDelaySeconds(long reconnectMaxDelaySeconds) {
        this.reconnectMaxDelaySeconds = reconnectMaxDelaySeconds;
    }

    public int getCommandDeduplicationSize() {
        return commandDeduplicationSize;
    }

    public void setCommandDeduplicationSize(int commandDeduplicationSize) {
        this.commandDeduplicationSize = commandDeduplicationSize;
    }

    public long getCodexRequestTimeoutSeconds() {
        return codexRequestTimeoutSeconds;
    }

    public void setCodexRequestTimeoutSeconds(long codexRequestTimeoutSeconds) {
        this.codexRequestTimeoutSeconds = codexRequestTimeoutSeconds;
    }

    public long getSkillMaxDownloadSizeMb() {
        return skillMaxDownloadSizeMb;
    }



    public void setSkillMaxDownloadSizeMb(long skillMaxDownloadSizeMb) {
        this.skillMaxDownloadSizeMb = skillMaxDownloadSizeMb;
    }

    public int getSkillMaxFileCount() {
        return skillMaxFileCount;
    }

    public void setSkillMaxFileCount(int skillMaxFileCount) {
        this.skillMaxFileCount = skillMaxFileCount;
    }

    public long getSkillMaxSingleFileSizeMb() {
        return skillMaxSingleFileSizeMb;
    }

    public void setSkillMaxSingleFileSizeMb(long skillMaxSingleFileSizeMb) {
        this.skillMaxSingleFileSizeMb = skillMaxSingleFileSizeMb;
    }

    public long getSkillMaxExpandedSizeMb() {
        return skillMaxExpandedSizeMb;
    }

    public void setSkillMaxExpandedSizeMb(long skillMaxExpandedSizeMb) {
        this.skillMaxExpandedSizeMb = skillMaxExpandedSizeMb;
    }

    public List<WorkspaceProperties> getWorkspaces() {
        return workspaces;
    }

    public void setWorkspaces(List<WorkspaceProperties> workspaces) {
        this.workspaces = workspaces;
    }

    public List<WorkspaceRootProperties> getWorkspaceRoots() {
        return workspaceRoots;
    }

    public void setWorkspaceRoots(List<WorkspaceRootProperties> workspaceRoots) {
        this.workspaceRoots = workspaceRoots;
    }
}
