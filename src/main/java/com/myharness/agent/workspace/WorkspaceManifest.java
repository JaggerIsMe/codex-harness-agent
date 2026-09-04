package com.myharness.agent.workspace;

import java.util.ArrayList;
import java.util.List;

public class WorkspaceManifest {
    private int version = 1;
    private List<DynamicWorkspaceRecord> entries = new ArrayList<>();

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public List<DynamicWorkspaceRecord> getEntries() { return entries; }
    public void setEntries(List<DynamicWorkspaceRecord> entries) {
        this.entries = entries == null ? new ArrayList<>() : entries;
    }
}
