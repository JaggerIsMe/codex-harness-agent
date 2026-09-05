package com.myharness.agent.entity.dto;

import java.util.List;
public record ArtifactManifestDTO(List<FileEntry> files) {
    public record FileEntry(String path,String name) {}
}
