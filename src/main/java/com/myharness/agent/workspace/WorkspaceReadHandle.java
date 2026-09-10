package com.myharness.agent.workspace;

import java.io.*;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.util.*;

/** Open streams through pinned Windows paths or relative SecureDirectoryStream handles. */
final class WorkspaceReadHandle implements AutoCloseable {
    private final List<AutoCloseable> owned=new ArrayList<>();
    private InputStream input;
    static WorkspaceReadHandle open(Path root,Path source) throws IOException {
        WorkspaceReadHandle result=new WorkspaceReadHandle();
        try {
            if(WindowsWorkspaceHandles.supported()) {
                result.owned.add(WindowsWorkspaceHandles.pin(root,source.getParent()));
                result.owned.add(WindowsWorkspaceHandles.entry(source,false));
                result.input=Files.newInputStream(source,LinkOption.NOFOLLOW_LINKS);
            }else {
                String rootIdentity=WorkspacePathPolicy.identity(root);
                DirectoryStream<Path> initial=Files.newDirectoryStream(root);result.owned.add(initial);
                if(!(initial instanceof SecureDirectoryStream<Path> current))
                    throw new WorkspaceFileException("UNSUPPORTED_FILESYSTEM","当前文件系统不支持安全的相对路径读取");
                Object opened=current.getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey();
                if(opened==null||!rootIdentity.equals(opened.toString()))throw new WorkspaceFileException("SOURCE_CHANGED","工作区身份发生变化");
                Path relative=root.relativize(source);int count=relative.getNameCount();
                for(int i=0;i<count-1;i++) {
                    current=current.newDirectoryStream(relative.getName(i),LinkOption.NOFOLLOW_LINKS);result.owned.add(current);
                }
                result.input=Channels.newInputStream(current.newByteChannel(relative.getFileName(),Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)));
            }
            return result;
        }catch(IOException|RuntimeException e){result.close();throw e;}
    }
    InputStream input(){return input;}
    @Override public void close() throws IOException {
        IOException failure=null;
        if(input!=null)try{input.close();}catch(IOException e){failure=e;}
        for(int i=owned.size()-1;i>=0;i--)try{owned.get(i).close();}catch(Exception e){if(failure==null)failure=new IOException(e);}
        if(failure!=null)throw failure;
    }
}
