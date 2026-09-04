package com.myharness.agent.entity.vo;

public class WorkspaceVO {

    private final String name;
    private final String rootPath;

    public WorkspaceVO(String name, String rootPath) {
        this.name = name;
        this.rootPath = rootPath;
    }

    public String getName() {
        return name;
    }

    public String getRootPath() {
        return rootPath;
    }
}
