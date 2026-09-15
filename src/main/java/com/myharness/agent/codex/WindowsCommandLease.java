package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myharness.agent.workspace.AgentStorage;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/** Private write-ahead record for ACL recovery after an Agent crash. */
final class WindowsCommandLease {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final Path file;
    private final ObjectNode record;

    WindowsCommandLease(Path temporary,String profile) throws IOException {
        Path root=AgentStorage.directory(temporary.getParent(),".command-leases");
        file=root.resolve(profile+".json");
        record=JSON.createObjectNode().put("profile",profile).put("pid",ProcessHandle.current().pid())
                .put("started",ProcessHandle.current().info().startInstant().orElseThrow().toString());
        record.putArray("paths");
        save();
    }

    void beforeGrant(Path path) throws IOException {
        record.withArray("paths").addObject().put("path",path.toString()).put("identity",identity(path));
        save();
    }

    void complete() throws IOException {Files.deleteIfExists(file);}

    private void save() throws IOException {
        Path temporary=file.resolveSibling(file.getFileName()+".part");
        Files.writeString(temporary,record.toString(),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }

    static void recover(Path data) throws IOException {
        Path root=data.resolve("skill-execution/.command-leases");
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return;
        if(!root.toAbsolutePath().normalize().equals(root.toRealPath()))throw new IOException("Command lease directory was redirected");
        try(var files=Files.list(root)) {
            for(Path file:files.filter(path->path.toString().endsWith(".json")).toList()) {
                if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)>128*1024)
                    throw new IOException("Invalid command lease");
                var record=JSON.readTree(Files.readString(file));
                String profile=record.path("profile").asText();
                if(!profile.matches("harness\\.command\\.[0-9a-f-]{36}") || !file.getFileName().toString().equals(profile+".json"))
                    throw new IOException("Invalid command profile record");
                var owner=ProcessHandle.of(record.path("pid").asLong());
                if(owner.isPresent() && owner.get().isAlive()
                        && owner.get().info().startInstant().map(Object::toString).orElse("").equals(record.path("started").asText()))continue;
                List<Path> paths=new ArrayList<>();
                for(var item:record.path("paths")) {
                    Path path=Path.of(item.path("path").asText());
                    if(!path.isAbsolute() || !path.equals(path.toRealPath()) || !identity(path).equals(item.path("identity").asText()))
                        throw new IOException("Command grant target changed; administrator cleanup required");
                    paths.add(path);
                }
                WindowsIsolatedCommand.revokeProfile(profile,paths);
                Files.delete(file);
            }
        }
    }

    private static String identity(Path path) throws IOException {
        return com.myharness.agent.workspace.WindowsWorkspaceHandles.identity(path);
    }
}
