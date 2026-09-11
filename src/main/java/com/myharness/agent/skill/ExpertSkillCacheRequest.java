package com.myharness.agent.skill;
/** Internal immutable cache request used only by expert runtime preparation. */
public record ExpertSkillCacheRequest(String skillId,String version,String downloadUrl,String sha256,String workspaceName) {}
