package com.myharness.agent.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.util.*;

/** Agent-owned paths are never resolved through a user Workspace. */
public final class AgentStorage {
    private AgentStorage() { }

    public static void protectDataDirectory(Path directory) throws IOException {
        if(Files.getFileStore(directory).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"));
        } else {
            var view=Files.getFileAttributeView(directory,AclFileAttributeView.class);
            if(view==null)throw new IOException("Agent data directory requires filesystem access controls");
            view.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(directory))
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .setFlags(AclEntryFlag.DIRECTORY_INHERIT,AclEntryFlag.FILE_INHERIT).build()));
            // Prevent a parent directory's broad grants from re-entering the private tree.
            var acl=new com.sun.jna.ptr.PointerByReference();var descriptor=new com.sun.jna.ptr.PointerByReference();
            var api=com.sun.jna.platform.win32.Advapi32.INSTANCE;
            int code=api.GetNamedSecurityInfo(directory.toString(),1,4,null,null,acl,null,descriptor);
            if(code!=0)throw new IOException("Cannot inspect Agent data ACL: "+code);
            try {
                code=api.SetNamedSecurityInfo(directory.toString(),1,4|0x80000000,null,null,acl.getValue(),null);
                if(code!=0)throw new IOException("Cannot protect Agent data ACL: "+code);
            } finally {com.sun.jna.platform.win32.Kernel32.INSTANCE.LocalFree(descriptor.getValue());}
        }
    }

    public static Path workspaceRoot(Path data, Path workspace) throws IOException {
        Path base=directory(data.toAbsolutePath().normalize(), "workspace-private");
        return directory(base,key(workspace.toRealPath().toString()));
    }

    public static Path executionDirectory(Path data, Path workspace) throws IOException {
        return directory(directory(data.toAbsolutePath().normalize(),"execution"),key(workspace.toRealPath().toString()));
    }

    public static String key(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException failure) {throw new IllegalStateException(failure);}
    }

    public static Path directory(Path root,String relative) throws IOException {
        Path base=root.toAbsolutePath().normalize();
        Path suffix=Path.of(relative);
        if(suffix.isAbsolute() || relative.isBlank() || suffix.normalize().startsWith(".."))throw new IOException("Invalid Agent storage path");
        Files.createDirectories(base);
        if(!base.equals(base.toRealPath()))throw new IOException("Agent storage cannot be redirected");
        Path current=base;
        for(Path segment:suffix) {
            if(segment.toString().equals("..") || segment.toString().equals("."))throw new IOException("Invalid Agent storage segment");
            current=current.resolve(segment);
            if(!current.startsWith(base))throw new IOException("Agent storage path escapes root");
            if(!Files.exists(current,LinkOption.NOFOLLOW_LINKS)) {
                if(Files.getFileStore(base).supportsFileAttributeView("posix"))
                    Files.createDirectory(current,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                else Files.createDirectory(current);
            }
            if(!Files.isDirectory(current,LinkOption.NOFOLLOW_LINKS) || !current.equals(current.toRealPath()))throw new IOException("Agent storage contains a link or special entry");
        }
        return current;
    }

}
