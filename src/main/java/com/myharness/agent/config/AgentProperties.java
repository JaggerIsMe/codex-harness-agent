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

    private Path skillInstallDir;

    @Min(1)
    private int maxConcurrentTurns = 1;

    private String codexCommand = "codex";

    private boolean strictProjectIsolation = true;

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

    public Path getSkillInstallDir() {
        return skillInstallDir;
    }

    public void setSkillInstallDir(Path skillInstallDir) {
        this.skillInstallDir = skillInstallDir;
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
