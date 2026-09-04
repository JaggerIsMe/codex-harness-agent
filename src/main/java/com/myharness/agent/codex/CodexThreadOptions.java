package com.myharness.agent.codex;

import java.nio.file.Path;

public class CodexThreadOptions {
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
