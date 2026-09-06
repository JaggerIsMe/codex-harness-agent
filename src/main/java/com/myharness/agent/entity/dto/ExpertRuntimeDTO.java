package com.myharness.agent.entity.dto;

public class ExpertRuntimeDTO {
    private int schemaVersion = 3;
    private String runtimeKey;
    public String getRuntimeKey() { return runtimeKey; }
    public void setRuntimeKey(String value) { runtimeKey = value; }
    private Long expertVersionId;
    private Long expertId;
    private boolean compatibleUpgrade;
    private Long projectRevision;
    private String name;
    private String systemPrompt;
    private java.util.List<ExpertRuntimeSkillDTO> skills = java.util.List.of();
    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int value) { schemaVersion = value; }
    public Long getExpertVersionId() { return expertVersionId; }
    public void setExpertVersionId(Long value) { expertVersionId = value; }
    public Long getExpertId() { return expertId; }
    public void setExpertId(Long value) { expertId = value; }
    public boolean isCompatibleUpgrade() { return compatibleUpgrade; }
    public void setCompatibleUpgrade(boolean value) { compatibleUpgrade = value; }
    public Long getProjectRevision() { return projectRevision; }
    public void setProjectRevision(Long value) { projectRevision = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String value) { systemPrompt = value; }
    public java.util.List<ExpertRuntimeSkillDTO> getSkills() { return skills; }
    public void setSkills(java.util.List<ExpertRuntimeSkillDTO> value) { skills = value; }
}
