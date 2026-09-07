package com.myharness.agent.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.agent.config.*;
import com.myharness.agent.entity.dto.WorkspaceFileCommandDTO;
import com.myharness.agent.entity.vo.DeviceIdentityVO;
import com.myharness.agent.security.DeviceIdentityProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceFileServiceTest {
    @TempDir Path temporary;
    Path root;
    WorkspaceFileService service;
    HttpServer server;
    WorkspaceFileCommandDTO current;
    byte[] content="hello workspace".getBytes();
    byte[] received;
    boolean revoked;
    ObjectMapper json=new ObjectMapper();

    @BeforeEach void setup() throws Exception {
        root=Files.createDirectory(temporary.resolve("workspace"));
        var p=new AgentProperties();p.setDataDir(temporary.resolve("agent"));
        var w=new WorkspaceProperties();w.setName("demo");w.setPath(root);p.setWorkspaces(List.of(w));
        var registry=new WorkspaceRegistry(p,json);
        var identity=mock(DeviceIdentityProvider.class);var device=mock(DeviceIdentityVO.class);
        when(identity.get()).thenReturn(device);when(device.getDeviceToken()).thenReturn("test-token");when(device.getDeviceCode()).thenReturn("device");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        p.setEnrollmentUrl(URI.create("http://127.0.0.1:"+server.getAddress().getPort()));
        server.createContext("/api/v1/agent/workspace-file-operations/",exchange -> {
            assertEquals("Bearer test-token",exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("device",exchange.getRequestHeaders().getFirst("X-Harness-Device-Code"));
            byte[] body;
            if(exchange.getRequestMethod().equals("PUT")) {received=exchange.getRequestBody().readAllBytes();body="{}".getBytes();}
            else if(exchange.getRequestURI().getPath().endsWith("/content")) body=content;
            else body=json.writeValueAsBytes(Map.of("status","success","data",current));
            exchange.sendResponseHeaders(revoked ? 403 : 200,body.length);
            try(var output=exchange.getResponseBody()){output.write(body);}
        });
        server.start();service=new WorkspaceFileService(registry,p,identity,json);
    }
    @AfterEach void stop() {server.stop(0);}
    WorkspaceFileCommandDTO command(String id,String path,String cursor,long size,String sha) {
        current=new WorkspaceFileCommandDTO(id,"1","demo",path,cursor,size,sha);return current;
    }
    String sha(byte[] value) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}

    @Test void paginatesWithoutMissingEntriesAndPreservesEmptyDirectories() throws Exception {
        for(int i=0;i<205;i++) Files.writeString(root.resolve(String.format("file-%03d.txt",i)),"x");
        Files.createDirectory(root.resolve("empty"));
        for(String name:List.of(".codex", ".git", ".harness", ".agent", ".agents")) Files.createDirectory(root.resolve(name));
        Files.writeString(root.resolve(".harness-workspace.json"),"{}");
        Files.writeString(root.resolve(".harness-upload-test.part"),"partial");
        var first=service.execute("SYNC_WORKSPACE_TREE",command("1","","",0,null));
        assertTrue(first.success(),first.error());assertEquals(200,first.entries().size());assertNotNull(first.nextCursor());
        var second=service.execute("SYNC_WORKSPACE_TREE",command("2","",first.nextCursor(),0,null));
        assertTrue(second.success(),second.error());assertEquals(6,second.entries().size());assertNull(second.nextCursor());
        Set<String> names=new HashSet<>();first.entries().forEach(e -> names.add(e.name()));second.entries().forEach(e -> names.add(e.name()));
        assertEquals(206,names.size());
        var empty=service.execute("SYNC_WORKSPACE_TREE",command("3","empty","",0,null));
        assertTrue(empty.success());assertEquals(List.of(),empty.entries());
    }
    @Test void createsFolderUploadsDownloadsAndNeverOverwritesOnRetry() throws Exception {
        assertTrue(service.execute("CREATE_WORKSPACE_DIRECTORY",command("1","docs","",0,null)).success());
        var c=command("2","docs/report.txt","",content.length,sha(content));
        assertTrue(service.execute("UPLOAD_WORKSPACE_FILE",c).success());
        assertArrayEquals(content,Files.readAllBytes(root.resolve("docs/report.txt")));
        assertFalse(Files.exists(root.resolve(".harness")));
        assertFalse(service.execute("UPLOAD_WORKSPACE_FILE",c).success());
        content="different bytes".getBytes();
        assertFalse(service.execute("UPLOAD_WORKSPACE_FILE",command("3","docs/report.txt","",content.length,sha(content))).success());
        assertEquals("hello workspace",Files.readString(root.resolve("docs/report.txt")));
        var downloaded=service.execute("PREPARE_WORKSPACE_DOWNLOAD",command("4","docs/report.txt","",0,null));
        assertTrue(downloaded.success(),downloaded.error());assertEquals("hello workspace",new String(received));
        assertEquals(sha(received),downloaded.sha256());
        try(var paths=Files.list(root.resolve("docs"))) {assertEquals(1,paths.count());}
    }
    @Test void hidesInternalFilesAtEveryLevelButKeepsOrdinaryDotfiles() throws Exception {
        Path docs=Files.createDirectory(root.resolve("docs"));
        for(String name:List.of(".CODEX", ".git", ".harness", ".agent", ".agents", ".gitignore", ".env.example"))
            Files.writeString(docs.resolve(name),"content");
        var tree=service.execute("SYNC_WORKSPACE_TREE",command("1","docs","",0,null));
        assertTrue(tree.success(),tree.error());
        assertEquals(List.of(".env.example",".gitignore"),tree.entries().stream().map(e -> e.name()).toList());
        Files.createDirectories(docs.resolve("sub/.harness/nested"));
        var hidden=service.execute("SYNC_WORKSPACE_TREE",command("2","docs/sub/.harness/nested","",0,null));
        assertFalse(hidden.success());assertTrue(hidden.entries().isEmpty());
    }
    @Test void checksumFailureAndRevocationLeaveNoWorkspaceFile() throws Exception {
        var failed=service.execute("UPLOAD_WORKSPACE_FILE",command("1","file.txt","",content.length,"0".repeat(64)));
        assertFalse(failed.success());assertFalse(Files.exists(root.resolve("file.txt")));
        revoked=true;
        assertFalse(service.execute("CREATE_WORKSPACE_DIRECTORY",command("2","forbidden","",0,null)).success());
        try(var paths=Files.list(root)) {assertEquals(0,paths.count());}
    }
    @Test void rejectsTraversalAbsolutePathsAndProtectedWrites() throws Exception {
        for(String path:List.of("../secret","/root/file","C:/file","a\\b","a//b","a/../b","file:stream","CON.txt","name. "))
            assertThrows(java.io.IOException.class,() -> service.checkedPath("demo",path,true),path);
        for(String path:List.of(".git/config",".codex/config.toml",".agents/skills/a",".harness-workspace.json"))
            assertThrows(java.io.IOException.class,() -> service.checkedPath("demo",path,true));
        assertThrows(RuntimeException.class,() -> service.checkedPath("other","file.txt",false));
    }
    @Test void refusesLinksEvenWhenTheyPointInsideWorkspace() throws Exception {
        var directory=Files.createDirectory(root.resolve("real"));
        try {Files.createSymbolicLink(root.resolve("link"),directory);}
        catch(java.io.IOException|UnsupportedOperationException e) {Assumptions.abort("当前账户不能创建符号链接");}
        assertThrows(java.io.IOException.class,() -> service.checkedPath("demo","link/file.txt",true));
        var tree=service.execute("SYNC_WORKSPACE_TREE",command("1","","",0,null));
        assertTrue(tree.entries().stream().anyMatch(e -> e.name().equals("link") && e.type().equals("UNAVAILABLE")));
    }
}
