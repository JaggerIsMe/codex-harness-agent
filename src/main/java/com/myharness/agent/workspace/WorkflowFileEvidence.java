package com.myharness.agent.workspace;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Bounded, no-link evidence captured while the Turn still owns its Workspace lease. */
public final class WorkflowFileEvidence {
    private static final int LIMIT=100*1024*1024;
    private WorkflowFileEvidence() {}
    public static String fingerprint(WorkspaceRegistry registry,String workspace,String relative) {
        try {
            if(relative==null || relative.isEmpty())return "UNVERIFIED";
            var policy=new WorkspacePathPolicy(registry);
            Path root=policy.checked(workspace,""),file=policy.checked(workspace,relative);
            if(Files.notExists(file,LinkOption.NOFOLLOW_LINKS))return "MISSING";
            var before=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!before.isRegularFile() || before.size()>LIMIT)return "UNVERIFIED";
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            if(WindowsWorkspaceHandles.supported()) {
                try(var files=new WorkspaceToolFiles(root,false)){digest.update(files.read(relative,LIMIT));}
            } else {
                if(((Number)Files.getAttribute(file,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue()!=1)return "UNVERIFIED";
                try(var handle=WorkspaceReadHandle.open(root,file)) {
                    byte[] buffer=new byte[8192];int n;long size=0;
                    while((n=handle.input().read(buffer))!=-1) {
                        if(Thread.currentThread().isInterrupted() || (size+=n)>LIMIT)return "UNVERIFIED";
                        digest.update(buffer,0,n);
                    }
                }
            }
            var after=Files.readAttributes(policy.checked(workspace,relative),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(before.size()!=after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !java.util.Objects.equals(before.fileKey(),after.fileKey()))return "UNVERIFIED";
            return after.size()==0?"EMPTY":HexFormat.of().formatHex(digest.digest());
        } catch(Exception failure) {return "UNVERIFIED";}
    }
}
