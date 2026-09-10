package com.myharness.agent.entity.vo;

public record WorkspaceFileEntryVO(String name, String path, String type, long sizeBytes, long modifiedAt, String entryRevision) {
    public WorkspaceFileEntryVO(String name,String path,String type,long sizeBytes,long modifiedAt) {
        this(name,path,type,sizeBytes,modifiedAt,null);
    }
}
