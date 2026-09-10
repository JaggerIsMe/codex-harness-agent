package com.myharness.agent.workspace;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

final class WorkspacePathPolicy {
    private static final Set<String> PROTECTED=Set.of(".codex",".git",".harness",".agent",".agents",".harness-workspace.json");
    private final WorkspaceRegistry workspaces;
    private final String instance=UUID.randomUUID().toString();
    WorkspacePathPolicy(WorkspaceRegistry workspaces){this.workspaces=workspaces;}
    static boolean visible(String path) {
        if(path==null)return false;
        for(String part:path.split("/")) {
            String lower=part.toLowerCase(Locale.ROOT);
            if(PROTECTED.contains(lower)||lower.startsWith(".harness-upload-"))return false;
        }
        return true;
    }
    Path checked(String workspace,String relative) throws IOException {
        WorkspaceFileService.validateRelative(relative);
        if(!visible(relative))throw new WorkspaceFileException("PROTECTED_PATH","不能访问受保护的工作区路径");
        Path root=workspaces.resolve(workspace,"").toRealPath();Path target=root;
        if(!relative.isEmpty())for(String segment:relative.split("/")) {
            target=target.resolve(segment);
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes a=Files.readAttributes(target,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                if(a.isSymbolicLink() || a.isOther() || !target.equals(target.toRealPath()))
                    throw new WorkspaceFileException("UNSUPPORTED_ENTRY","不支持链接、重解析路径或特殊文件");
            }
        }
        if(!target.startsWith(root)||!target.equals(workspaces.resolve(workspace,relative)))
            throw new WorkspaceFileException("INVALID_PATH","路径超出工作区");
        return target;
    }
    String revision(String workspace,String relative) throws IOException {
        Path path=checked(workspace,relative);
        BasicFileAttributes a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        return digest((instance+"\n"+workspace+"\n"+path+"\n"+identity(path)+"\n"+a.size()+"\n"+a.lastModifiedTime()+"\n"+a.creationTime()).getBytes(StandardCharsets.UTF_8));
    }
    void expected(String workspace,String relative,String expected) throws IOException {
        if(expected==null || !expected.equals(revision(workspace,relative)))
            throw new WorkspaceFileException("SOURCE_CHANGED","文件已发生变化，请刷新目录后重试");
    }
    static String identity(Path path) throws IOException {
        if(WindowsWorkspaceHandles.supported())return WindowsWorkspaceHandles.identity(path);
        Object key=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();
        if(key==null)throw new WorkspaceFileException("UNSUPPORTED_FILESYSTEM","文件系统不能提供可靠的文件身份");
        return key.toString();
    }
    static String digest(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
