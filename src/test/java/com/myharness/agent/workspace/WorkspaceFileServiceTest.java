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
    boolean failItems;
    byte[] receivedItems;
    AgentProperties properties;
    WorkspaceRegistry registry;
    DeviceIdentityProvider identity;
    WorkspaceExecutionCoordinator coordinator;
    ObjectMapper json=new ObjectMapper();

    @BeforeEach void setup() throws Exception {
        root=Files.createDirectory(temporary.resolve("workspace"));
        var p=new AgentProperties();properties=p;p.setDataDir(temporary.resolve("agent"));
        var w=new WorkspaceProperties();w.setName("demo");w.setPath(root);p.setWorkspaces(List.of(w));
        registry=new WorkspaceRegistry(p,json);
        identity=mock(DeviceIdentityProvider.class);var device=mock(DeviceIdentityVO.class);
        when(identity.get()).thenReturn(device);when(device.getDeviceToken()).thenReturn("test-token");when(device.getDeviceCode()).thenReturn("device");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        p.setEnrollmentUrl(URI.create("http://127.0.0.1:"+server.getAddress().getPort()));
        server.createContext("/api/v1/agent/workspace-file-operations/",exchange -> {
            assertEquals("Bearer test-token",exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("device",exchange.getRequestHeaders().getFirst("X-Harness-Device-Code"));
            byte[] body;
            if(exchange.getRequestMethod().equals("PUT")) {
                byte[] uploaded=exchange.getRequestBody().readAllBytes();
                if(exchange.getRequestURI().getPath().endsWith("/items"))receivedItems=uploaded;else received=uploaded;
                body="{}".getBytes();
            }
            else if(exchange.getRequestURI().getPath().endsWith("/content")) body=content;
            else body=json.writeValueAsBytes(Map.of("status","success","data",current));
            exchange.sendResponseHeaders(revoked || failItems&&exchange.getRequestURI().getPath().endsWith("/items") ? 403 : 200,body.length);
            try(var output=exchange.getResponseBody()){output.write(body);}
        });
        server.start();coordinator=new WorkspaceExecutionCoordinator();service=new WorkspaceFileService(registry,p,identity,json,coordinator);
    }
    @AfterEach void stop() {server.stop(0);}
    WorkspaceFileCommandDTO command(String id,String path,String cursor,long size,String sha) {
        current=new WorkspaceFileCommandDTO(id,"1","demo",path,cursor,size,sha);return current;
    }
    String sha(byte[] value) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}

    String revision(String path) throws Exception {
        String parent=path.contains("/")?path.substring(0,path.lastIndexOf('/')):"";
        var result=service.execute("SYNC_WORKSPACE_TREE",command("9000",parent,null,0,null));
        assertTrue(result.success(),result.error());
        return result.entries().stream().filter(e->e.path().equals(path)).findFirst().orElseThrow().entryRevision();
    }
    WorkspaceFileCommandDTO action(String id,String path,String target,String revision,String planId,String planDigest,
            List<com.myharness.agent.entity.dto.WorkspaceArchiveItemDTO> items) {
        current=new WorkspaceFileCommandDTO(id,"1","demo",path,null,0,null,target,revision,planId,planDigest,items,"a".repeat(64),
                new com.myharness.agent.entity.dto.WorkspaceFileLimitsDTO(100,20*1024*1024,100*1024*1024,110*1024*1024,512*1024,300),null);
        return current;
    }
    @Test void renameUsesObjectHandleAndReplaysWithoutOverwritingReplacement() throws Exception {
        Assumptions.assumeTrue(WindowsWorkspaceHandles.supported());
        Files.writeString(root.resolve("a.txt"),"first");
        var c=action("101","a.txt","b.txt",revision("a.txt"),null,null,null);
        var moved=service.execute("RELOCATE_WORKSPACE_ENTRY",c);
        assertTrue(moved.success(),moved.error());assertEquals("COMPLETE",moved.outcome());assertNotNull(moved.entryRevision());
        Files.writeString(root.resolve("a.txt"),"replacement");
        assertEquals(moved,service.execute("RELOCATE_WORKSPACE_ENTRY",c));
        assertEquals("replacement",Files.readString(root.resolve("a.txt")));assertEquals("first",Files.readString(root.resolve("b.txt")));
        var conflict=service.execute("RELOCATE_WORKSPACE_ENTRY",action("102","a.txt","b.txt",revision("a.txt"),null,null,null));
        assertFalse(conflict.success());assertEquals("TARGET_EXISTS",conflict.code());assertEquals("first",Files.readString(root.resolve("b.txt")));
    }
    @Test void directoryRenameAndSingleFileMovePreserveContentsAndRejectProtectedDescendants() throws Exception {
        Assumptions.assumeTrue(WindowsWorkspaceHandles.supported());
        Files.createDirectories(root.resolve("docs/sub"));Files.writeString(root.resolve("docs/sub/a.txt"),"content");
        var renamed=service.execute("RELOCATE_WORKSPACE_ENTRY",action("110","docs","reports",revision("docs"),null,null,null));
        assertTrue(renamed.success(),renamed.error());assertEquals("content",Files.readString(root.resolve("reports/sub/a.txt")));
        var moved=service.execute("RELOCATE_WORKSPACE_ENTRY",action("111","reports/sub/a.txt","a.txt",revision("reports/sub/a.txt"),null,null,null));
        assertTrue(moved.success(),moved.error());
        Files.createDirectory(root.resolve("reports/.agent"));
        var blocked=service.execute("RELOCATE_WORKSPACE_ENTRY",action("112","reports","secret",revision("reports"),null,null,null));
        assertEquals("PROTECTED_PATH",blocked.code());assertTrue(Files.exists(root.resolve("reports/.agent")));
    }
    @Test void deleteChecksWholeSubtreeAndConsumesEachPlanOnlyOnce() throws Exception {
        Assumptions.assumeTrue(WindowsWorkspaceHandles.supported());
        Files.createDirectories(root.resolve("docs/sub"));Files.writeString(root.resolve("docs/sub/a.txt"),"content");
        var planned=service.execute("PREPARE_WORKSPACE_DELETE",action("120","docs",null,revision("docs"),null,null,null));
        assertTrue(planned.success(),planned.error());assertEquals(1,planned.plan().fileCount());assertEquals(2,planned.plan().directoryCount());
        var deletion=action("121","docs",null,null,planned.plan().planId(),planned.plan().planDigest(),null);
        var deleted=service.execute("DELETE_WORKSPACE_ENTRY",deletion);
        assertTrue(deleted.success(),deleted.error());assertFalse(Files.exists(root.resolve("docs")));
        assertEquals(1,deleted.summary().deletedFiles());assertEquals(2,deleted.summary().deletedDirectories());assertNotNull(receivedItems);
        Files.createDirectory(root.resolve("docs"));Files.writeString(root.resolve("docs/new.txt"),"new");
        current=deletion;assertEquals(deleted,service.execute("DELETE_WORKSPACE_ENTRY",deletion));
        assertEquals("new",Files.readString(root.resolve("docs/new.txt")));
        var duplicate=service.execute("DELETE_WORKSPACE_ENTRY",action("122","docs",null,null,planned.plan().planId(),planned.plan().planDigest(),null));
        assertEquals("PLAN_CONSUMED",duplicate.code());assertTrue(Files.exists(root.resolve("docs/new.txt")));
    }
    @Test void changedDeletePlanFailsBeforeRemovingAnything() throws Exception {
        Assumptions.assumeTrue(WindowsWorkspaceHandles.supported());
        Files.createDirectory(root.resolve("docs"));Files.writeString(root.resolve("docs/a.txt"),"content");
        var planned=service.execute("PREPARE_WORKSPACE_DELETE",action("130","docs",null,revision("docs"),null,null,null));
        Files.writeString(root.resolve("docs/new.txt"),"new");
        var result=service.execute("DELETE_WORKSPACE_ENTRY",action("131","docs",null,null,planned.plan().planId(),planned.plan().planDigest(),null));
        assertEquals("SOURCE_CHANGED",result.code());assertEquals("NO_CHANGE",result.outcome());assertTrue(Files.exists(root.resolve("docs/a.txt")));
    }
    @Test void resultUploadFailureReconcilesDurableOutcomeWithoutDeletingNewFileAfterRestart() throws Exception {
        Assumptions.assumeTrue(WindowsWorkspaceHandles.supported());Files.writeString(root.resolve("a.txt"),"original");
        var planned=service.execute("PREPARE_WORKSPACE_DELETE",action("140","a.txt",null,revision("a.txt"),null,null,null));
        var deletion=action("141","a.txt",null,null,planned.plan().planId(),planned.plan().planDigest(),null);
        failItems=true;assertEquals("UNKNOWN",service.execute("DELETE_WORKSPACE_ENTRY",deletion).status());
        assertThrows(com.myharness.agent.command.AgentOperationException.class,()->coordinator.enterTurn(root));
        failItems=false;Files.writeString(root.resolve("a.txt"),"replacement");
        coordinator=new WorkspaceExecutionCoordinator();service=new WorkspaceFileService(registry,properties,identity,json,coordinator);
        current=new WorkspaceFileCommandDTO(deletion.operationId(),deletion.projectId(),deletion.workspaceName(),deletion.path(),deletion.cursor(),0,null,null,null,
                deletion.planId(),deletion.planDigest(),null,deletion.requestDigest(),deletion.limits(),deletion.operationId());
        var result=service.execute("RECONCILE_WORKSPACE_OPERATION",current);
        assertTrue(result.success(),result.error());assertEquals("replacement",Files.readString(root.resolve("a.txt")));
    }
    @Test void archiveRetainsUnicodeRelativePathsAndUsesIndependentSizeLimit() throws Exception {
        Files.createDirectories(root.resolve("报告"));Files.createDirectories(root.resolve("other"));
        Files.writeString(root.resolve("报告/a.txt"),"甲");Files.writeString(root.resolve("other/a.txt"),"乙");
        var items=List.of(new com.myharness.agent.entity.dto.WorkspaceArchiveItemDTO("报告/a.txt",revision("报告/a.txt")),
                new com.myharness.agent.entity.dto.WorkspaceArchiveItemDTO("other/a.txt",revision("other/a.txt")));
        properties.setMaxAttachmentBytes(4); // Each source fits; the ZIP is larger than ordinary upload limit.
        var c=action("150","",null,null,null,null,items);
        var result=service.execute("PREPARE_WORKSPACE_ARCHIVE",c);assertTrue(result.success(),result.error());assertTrue(result.sizeBytes()>4);
        Map<String,String> unpacked=new HashMap<>();
        try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(received),java.nio.charset.StandardCharsets.UTF_8)) {
            java.util.zip.ZipEntry e;while((e=zip.getNextEntry())!=null)unpacked.put(e.getName(),new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals(Map.of("报告/a.txt","甲","other/a.txt","乙"),unpacked);
        assertEquals(sha(received),result.sha256());
    }
    @Test void archiveFailsEntirePackageForStaleFileAndRejectsDirectories() throws Exception {
        Files.writeString(root.resolve("a.txt"),"a");String old=revision("a.txt");Files.writeString(root.resolve("a.txt"),"changed");
        var failed=service.execute("PREPARE_WORKSPACE_ARCHIVE",action("160","",null,null,null,null,List.of(new com.myharness.agent.entity.dto.WorkspaceArchiveItemDTO("a.txt",old))));
        assertFalse(failed.success());assertEquals("SOURCE_CHANGED",failed.code());assertNull(received);
        Files.createDirectory(root.resolve("folder"));
        var directory=service.execute("PREPARE_WORKSPACE_ARCHIVE",action("161","",null,null,null,null,List.of(new com.myharness.agent.entity.dto.WorkspaceArchiveItemDTO("folder",revision("folder")))));
        assertFalse(directory.success());assertEquals("UNSUPPORTED_ENTRY",directory.code());
    }
    @Test void workspaceTurnLeaseRejectsMutationAcrossConversations() throws Exception {
        Assumptions.assumeTrue(WindowsWorkspaceHandles.supported());Files.writeString(root.resolve("a.txt"),"a");
        var c=action("170","a.txt","b.txt",revision("a.txt"),null,null,null);
        try(var turn=coordinator.enterTurn(root)) {
            var result=service.execute("RELOCATE_WORKSPACE_ENTRY",c);assertEquals("WORKSPACE_BUSY",result.code());assertTrue(Files.exists(root.resolve("a.txt")));
        }
        assertTrue(service.execute("RELOCATE_WORKSPACE_ENTRY",c).success());
    }
    @Test void partialDeleteStopsOnLockedEntryAndNeverReplaysEffects() throws Exception {
        Assumptions.assumeTrue(WindowsWorkspaceHandles.supported());
        Files.createDirectory(root.resolve("docs"));Files.writeString(root.resolve("docs/a.txt"),"locked");Files.writeString(root.resolve("docs/b.txt"),"delete first");
        var planned=service.execute("PREPARE_WORKSPACE_DELETE",action("180","docs",null,revision("docs"),null,null,null));
        var deletion=action("181","docs",null,null,planned.plan().planId(),planned.plan().planDigest(),null);
        com.myharness.agent.entity.dto.WorkspaceFileResultDTO result;
        try(var external=WindowsWorkspaceHandles.entry(root.resolve("docs/a.txt"),false)) {
            result=service.execute("DELETE_WORKSPACE_ENTRY",deletion);
            assertEquals("PARTIAL_FAILED",result.status());assertEquals("PARTIAL",result.outcome());assertEquals(1,result.summary().deletedFiles());
            assertTrue(Files.exists(root.resolve("docs/a.txt")));assertFalse(Files.exists(root.resolve("docs/b.txt")));
        }
        Files.writeString(root.resolve("docs/b.txt"),"replacement");
        assertEquals(result,service.execute("DELETE_WORKSPACE_ENTRY",deletion));
        assertEquals("replacement",Files.readString(root.resolve("docs/b.txt")));assertTrue(Files.exists(root.resolve("docs/a.txt")));
    }
    @Test void persistedClaimWithoutResultRestoresUnknownAndCannotRepeatMutation() throws Exception {
        Assumptions.assumeTrue(WindowsWorkspaceHandles.supported());Files.writeString(root.resolve("a.txt"),"original");
        var c=action("190","a.txt","b.txt",revision("a.txt"),null,null,null);
        var journal=new WorkspaceOperationJournal(properties.getDataDir(),json);
        journal.claim(new WorkspaceOperationJournal.Claim(c.operationId(),journal.digest("RELOCATE_WORKSPACE_ENTRY",c),"demo",root.toString(),"RELOCATE_WORKSPACE_ENTRY"));
        coordinator=new WorkspaceExecutionCoordinator();service=new WorkspaceFileService(registry,properties,identity,json,coordinator);
        assertEquals("UNKNOWN",service.execute("RELOCATE_WORKSPACE_ENTRY",c).status());
        assertThrows(com.myharness.agent.command.AgentOperationException.class,()->coordinator.enterTurn(root));
        assertTrue(Files.exists(root.resolve("a.txt")));assertFalse(Files.exists(root.resolve("b.txt")));
    }
    @Test void planCannotSurviveAgentRestartAndExpiredTemporaryFilesAreBoundedlyCleaned() throws Exception {
        Assumptions.assumeTrue(WindowsWorkspaceHandles.supported());Files.writeString(root.resolve("a.txt"),"original");
        var planned=service.execute("PREPARE_WORKSPACE_DELETE",action("200","a.txt",null,revision("a.txt"),null,null,null));
        var c=action("201","a.txt",null,null,planned.plan().planId(),planned.plan().planDigest(),null);
        coordinator=new WorkspaceExecutionCoordinator();service=new WorkspaceFileService(registry,properties,identity,json,coordinator);
        assertEquals("PLAN_EXPIRED",service.execute("DELETE_WORKSPACE_ENTRY",c).code());assertTrue(Files.exists(root.resolve("a.txt")));
        Path planFile=properties.getDataDir().resolve("workspace-delete-plans").resolve(planned.plan().planId()+".json");
        Files.setLastModifiedTime(planFile,java.nio.file.attribute.FileTime.fromMillis(0));
        Path untouched=properties.getDataDir().resolve("unrelated.json");Files.writeString(untouched,"keep");Files.setLastModifiedTime(untouched,java.nio.file.attribute.FileTime.fromMillis(0));
        service.cleanupActionTemporaryFiles();assertFalse(Files.exists(planFile));assertTrue(Files.exists(untouched));
    }
    @Test void sameOperationIdCannotReplayAResultForDifferentArchiveSelection() throws Exception {
        Files.writeString(root.resolve("a.txt"),"a");Files.writeString(root.resolve("b.txt"),"b");
        var first=service.execute("PREPARE_WORKSPACE_ARCHIVE",action("210","",null,null,null,null,List.of(new com.myharness.agent.entity.dto.WorkspaceArchiveItemDTO("a.txt",revision("a.txt")))));
        assertTrue(first.success(),first.error());
        var altered=service.execute("PREPARE_WORKSPACE_ARCHIVE",action("210","",null,null,null,null,List.of(new com.myharness.agent.entity.dto.WorkspaceArchiveItemDTO("b.txt",revision("b.txt")))));
        assertEquals("REQUEST_CONFLICT",altered.code());
    }
    @Test void rejectedAuthorizationReportsNoEffectButReconciliationRemainsUnknown() throws Exception {
        Files.writeString(root.resolve("a.txt"),"a");var c=action("220","a.txt","b.txt",revision("a.txt"),null,null,null);revoked=true;
        var result=service.execute("RELOCATE_WORKSPACE_ENTRY",c);assertEquals(1,result.version());assertEquals("FAILED",result.status());assertEquals("NO_CHANGE",result.outcome());
        assertTrue(Files.exists(root.resolve("a.txt")));assertFalse(Files.exists(root.resolve("b.txt")));
        current=new WorkspaceFileCommandDTO(c.operationId(),c.projectId(),c.workspaceName(),c.path(),c.cursor(),0,null,c.targetPath(),c.expectedRevision(),null,null,null,c.requestDigest(),c.limits(),c.operationId());
        assertEquals("UNKNOWN",service.execute("RECONCILE_WORKSPACE_OPERATION",current).status());
    }

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
