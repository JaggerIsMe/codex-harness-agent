package com.myharness.agent.config;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.nio.file.Path;

public class WorkspaceProperties {

    @NotBlank
    private String name;

    @NotNull
    private Path path;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Path getPath() {
        return path;
    }

    public void setPath(Path path) {
        this.path = path;
    }
}
