package com.myharness.agent.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A short-lived, pinned set of regular Workspace files for trusted dynamic tools. */
public final class WorkspaceToolFiles implements AutoCloseable {
    private final Path root;
    private final boolean writable;
    private final List<AutoCloseable> owned=new ArrayList<>();
    private final Map<String,WindowsWorkspaceHandles.DataEntry> entries=new HashMap<>();

    public WorkspaceToolFiles(Path workspace,boolean writable) throws IOException {
        root=workspace.toRealPath();this.writable=writable;
        owned.add(WindowsWorkspaceHandles.pin(root,root));
    }
    private Path checked(String relative) throws IOException {
        WorkspaceFileService.validateRelative(relative);
        if(relative.isEmpty())throw new IOException("工具需要工作区内的文件路径");
        Path path=root.resolve(relative).normalize();
        if(!path.startsWith(root))throw new IOException("路径超出工作区");
        owned.add(WindowsWorkspaceHandles.pin(root,path.getParent()));
        if(!path.getParent().equals(path.getParent().toRealPath()))throw new IOException("不支持目录别名路径");
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)&&!path.equals(path.toRealPath()))throw new IOException("不支持文件别名路径");
        return path;
    }
    public byte[] read(String relative,int limit) throws IOException {
        if(entries.containsKey(relative))throw new IOException("文件重复打开");
        var file=WindowsWorkspaceHandles.data(checked(relative),writable,false);
        owned.add(file);entries.put(relative,file);
        if(!root.resolve(relative).equals(root.resolve(relative).toRealPath()))throw new IOException("不支持文件别名路径");
        return file.read(limit);
    }
    public void requireAbsent(String relative) throws IOException {
        if(Files.exists(checked(relative),LinkOption.NOFOLLOW_LINKS))throw new IOException("目标文件已存在："+relative);
    }
    public void create(String relative,byte[] bytes) throws IOException {
        requireWritable();
        var file=WindowsWorkspaceHandles.data(checked(relative),true,true);owned.add(file);entries.put(relative,file);
        try {file.write(bytes);}catch(IOException failure){try{file.delete();}catch(IOException ignored){ }throw failure;}
    }
    public void replace(String relative,byte[] bytes) throws IOException {requireWritable();entry(relative).write(bytes);}
    public void remove(String relative) throws IOException {requireWritable();entry(relative).delete();}
    public void move(String source,String destination) throws IOException {
        requireWritable();requireAbsent(destination);entry(source).relocate(checked(destination));
    }
    private WindowsWorkspaceHandles.DataEntry entry(String path) throws IOException {
        var value=entries.get(path);if(value==null)throw new IOException("文件尚未打开并锁定");return value;
    }
    private void requireWritable() throws IOException {if(!writable)throw new IOException("当前工具只能读取文件");}
    @Override public void close() {
        for(int i=owned.size()-1;i>=0;i--)try{owned.get(i).close();}catch(Exception ignored){ }
        owned.clear();entries.clear();
    }
}
