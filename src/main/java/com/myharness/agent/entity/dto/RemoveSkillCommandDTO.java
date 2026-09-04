package com.myharness.agent.entity.dto;

public class RemoveSkillCommandDTO {
    private String skillId;
    private String skillName;
    private String version;
    private String scopeType;
    private String workspaceName;

    public String getSkillId() { return skillId; }
    public void setSkillId(String skillId) { this.skillId = skillId; }
    public String getSkillName() { return skillName; }
    public void setSkillName(String skillName) { this.skillName = skillName; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getScopeType() { return scopeType; }
    public void setScopeType(String scopeType) { this.scopeType = scopeType; }
    public String getWorkspaceName() { return workspaceName; }
    public void setWorkspaceName(String workspaceName) { this.workspaceName = workspaceName; }
}
