package com.myharness.agent.entity.dto;

public class SkillResultEventDTO {
    private final String skillId;
    private final String version;
    private final boolean success;
    private final String installedPath;
    private final String error;

    public SkillResultEventDTO(String skillId, String version, boolean success, String installedPath, String error) {
        this.skillId = skillId;
        this.version = version;
        this.success = success;
        this.installedPath = installedPath;
        this.error = error;
    }

    public String getSkillId() { return skillId; }
    public String getVersion() { return version; }
    public boolean isSuccess() { return success; }
    public String getInstalledPath() { return installedPath; }
    public String getError() { return error; }
}
