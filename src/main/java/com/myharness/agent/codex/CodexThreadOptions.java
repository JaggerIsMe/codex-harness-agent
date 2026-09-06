package com.myharness.agent.codex;

import java.nio.file.Path;

public class CodexThreadOptions {
    private boolean isolatedExpertRuntime;
    private java.util.List<CodexSkillInput> expertSkills=java.util.List.of();
    public CodexThreadOptions withExpertSkills(java.util.List<CodexSkillInput> value) {
        isolatedExpertRuntime=true;expertSkills=java.util.List.copyOf(value);return this;
    }
    public boolean isIsolatedExpertRuntime() { return isolatedExpertRuntime; }
    public java.util.List<CodexSkillInput> getExpertSkills() { return expertSkills; }
    private final String projectId;
    private final Path workspace;
    private final String model;

    public CodexThreadOptions(String projectId, Path workspace, String model) {
        this.projectId = projectId;
        this.workspace = workspace;
        this.model = model;
    }

    public String getProjectId() { return projectId; }
    public Path getWorkspace() { return workspace; }
    public String getModel() { return model; }
}
