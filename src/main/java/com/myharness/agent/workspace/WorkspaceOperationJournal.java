package com.myharness.agent.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.agent.entity.dto.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.List;

/** A claim without a durable result is unresolved, including after process restart. */
final class WorkspaceOperationJournal {
    record Claim(String operationId,String digest,String workspaceName,String root,String kind) {}
    record Terminal(WorkspaceFileResultDTO result,List<WorkspaceFileItemResultDTO> items) {}
    private final Path directory;private final ObjectMapper json;
    WorkspaceOperationJournal(Path dataDir,ObjectMapper json) throws IOException {
        this.directory=dataDir.resolve("workspace-action-journal");this.json=json;Files.createDirectories(directory);
    }
    String digest(String kind,WorkspaceFileCommandDTO command) throws IOException {
        return WorkspacePathPolicy.digest(json.writeValueAsBytes(new Digest(kind,command.original())));
    }
    record Digest(String kind,WorkspaceFileCommandDTO command) {}
    Claim claim(String id) throws IOException {Path path=directory.resolve(id+".claim");return Files.exists(path)?json.readValue(Files.readAllBytes(path),Claim.class):null;}
    void claim(Claim claim) throws IOException {writeNew(directory.resolve(claim.operationId()+".claim"),json.writeValueAsBytes(claim));}
    Terminal terminal(String id) throws IOException {Path path=directory.resolve(id+".result");return Files.exists(path)?json.readValue(Files.readAllBytes(path),Terminal.class):null;}
    void complete(String id,Terminal terminal) throws IOException {
        Path temporary=Files.createTempFile(directory,id+"-",".part");
        try {write(temporary,json.writeValueAsBytes(terminal));Files.move(temporary,directory.resolve(id+".result"),StandardCopyOption.ATOMIC_MOVE);}
        finally {Files.deleteIfExists(temporary);}
    }
    void restore(WorkspaceExecutionCoordinator coordinator) throws IOException {
        try(var stream=Files.newDirectoryStream(directory,"*.claim")) {
            for(Path path:stream) {
                Claim claim=json.readValue(Files.readAllBytes(path),Claim.class);
                if(WorkspaceFileActions.mutation(claim.kind())&&terminal(claim.operationId())==null)coordinator.holdUnknown(Path.of(claim.root()),claim.operationId());
            }
        }
    }
    static void writeNew(Path path,byte[] bytes) throws IOException {
        try(var channel=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
            ByteBuffer data=ByteBuffer.wrap(bytes);while(data.hasRemaining())channel.write(data);channel.force(true);
        }
    }
    private static void write(Path path,byte[] bytes) throws IOException {
        try(var channel=FileChannel.open(path,StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE)) {
            ByteBuffer data=ByteBuffer.wrap(bytes);while(data.hasRemaining())channel.write(data);channel.force(true);
        }
    }
}
