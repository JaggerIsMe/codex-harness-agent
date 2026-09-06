package com.myharness.agent.entity.dto;

public class ExpertRuntimeSkillDTO {
    private Long skillId;
    private Long versionId;
    private String name;
    private String version;
    private String sha256;
    private String downloadUrl;
    public Long getSkillId() { return skillId; }
    public void setSkillId(Long value) { skillId = value; }
    public Long getVersionId() { return versionId; }
    public void setVersionId(Long value) { versionId = value; }
    public String getName() { return name; }
    public void setName(String value) { name = value; }
    public String getVersion() { return version; }
    public void setVersion(String value) { version = value; }
    public String getSha256() { return sha256; }
    public void setSha256(String value) { sha256 = value; }
    public String getDownloadUrl() { return downloadUrl; }
    public void setDownloadUrl(String value) { downloadUrl = value; }
}

