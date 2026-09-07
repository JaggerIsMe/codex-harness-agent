package com.myharness.agent.attachment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.agent.command.AgentOperationException;
import com.myharness.agent.config.*;
import com.myharness.agent.entity.dto.*;
import com.myharness.agent.entity.vo.DeviceIdentityVO;
import com.myharness.agent.security.DeviceIdentityProvider;
import com.myharness.agent.workspace.WorkspaceRegistry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationAttachmentServiceTest {
    @TempDir Path temp;
    HttpServer server; Path workspace; StartTurnCommandDTO command; ConversationAttachmentService service;
    byte[] bytes="hello attachment".getBytes(); AtomicInteger downloads=new AtomicInteger(); AtomicInteger manifests=new AtomicInteger();
    volatile boolean corrupt; volatile boolean revoked;
    @BeforeEach void setup() throws Exception {
        workspace=Files.createDirectory(temp.resolve("workspace"));
        var props=new AgentProperties();props.setDataDir(temp.resolve("agent-data"));
        var w=new WorkspaceProperties();w.setName("demo");w.setPath(workspace);props.setWorkspaces(List.of(w));
        var registry=new WorkspaceRegistry(props,new ObjectMapper());
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        props.setEnrollmentUrl(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api/v1/agent/enroll"));
        var provider=mock(DeviceIdentityProvider.class);var device=mock(DeviceIdentityVO.class);
        when(provider.get()).thenReturn(device);when(device.getDeviceToken()).thenReturn("test-token");when(device.getDeviceCode()).thenReturn("test-device");
        service=new ConversationAttachmentService(props,provider,registry,new ObjectMapper());
        command=new StartTurnCommandDTO();command.setConversationId("3");command.setTurnId("7");command.setWorkspaceName("demo");command.setMessage("Read this");
        var attachment=new TurnAttachmentDTO("9","hello.txt","application/octet-stream",bytes.length,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        command.setAttachments(List.of(attachment));
        server.createContext("/api/v1/agent/turns/7/attachments",exchange -> {
            assertEquals("Bearer test-token",exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("test-device",exchange.getRequestHeaders().getFirst("X-Harness-Device-Code"));
            byte[] body;
            if(exchange.getRequestURI().getPath().endsWith("/download")){downloads.incrementAndGet();body=corrupt ? new byte[bytes.length] : bytes;}
            else {manifests.incrementAndGet();body=new ObjectMapper().writeValueAsBytes(Map.of("status","success","data",command.getAttachments()));}
            exchange.sendResponseHeaders(revoked ? 403 : 200,body.length);try(var out=exchange.getResponseBody()){out.write(body);}
        });server.start();
    }
    @AfterEach void close(){server.stop(0);}
    @Test void downloadsVerifiedFileAndRechecksAuthorizationForCache() throws Exception {
        String input=service.prepare(command,new AttachmentPreparation());
        assertTrue(input.contains(".harness/attachments/3/9/file-hello.txt"));
        assertArrayEquals(bytes,Files.readAllBytes(workspace.resolve(".harness/attachments/3/9/file-hello.txt")));
        service.prepare(command,new AttachmentPreparation());assertEquals(1,downloads.get());assertEquals(4,manifests.get());
        revoked=true;assertThrows(AgentOperationException.class,() -> service.prepare(command,new AttachmentPreparation()));
    }
    @Test void rejectsCorruptBytesAndCleansPartialFile() throws Exception {
        corrupt=true;
        assertThrows(AgentOperationException.class,() -> service.prepare(command,new AttachmentPreparation()));
        try(var paths=Files.walk(workspace)){assertFalse(paths.anyMatch(Files::isRegularFile));}
    }
    @Test void cancellationPreventsNetworkAndPreparation() {
        var preparation=new AttachmentPreparation();preparation.cancel();
        assertThrows(AgentOperationException.class,() -> service.prepare(command,preparation));assertEquals(0,downloads.get());
    }
    @Test void unsafeIdentifiersNeverBecomePathsOrUrls() {
        command.setTurnId("../8");assertThrows(AgentOperationException.class,() -> service.prepare(command,new AttachmentPreparation()));assertEquals(0,manifests.get());
    }
    @Test void durableClaimRefusesSecondExecution() {
        service.claim(command);assertThrows(AgentOperationException.class,() -> service.claim(command));
    }
    @Test void workspaceImageUsesNativeInputWithUnchangedTextAndNoDownload() throws Exception {
        Files.write(workspace.resolve("photo.png"),bytes);
        var old=command.getAttachments().getFirst();
        command.setAttachments(List.of(new TurnAttachmentDTO(old.id(),"photo.png","image/png",old.sizeBytes(),old.sha256(),"photo.png")));
        command.setMessage("分析这张图片");
        var prepared=service.prepareInput(command,new AttachmentPreparation());
        assertEquals("分析这张图片",prepared.message());
        assertEquals(List.of(workspace.resolve("photo.png").toString()),prepared.localImages());
        assertEquals(0,downloads.get());assertFalse(Files.exists(workspace.resolve(".harness")));
    }
    @Test void ordinaryWorkspaceFilesKeepAssociationOrderAndRejectChangedContents() throws Exception {
        Files.write(workspace.resolve("data.txt"),bytes);
        var old=command.getAttachments().getFirst();
        command.setAttachments(List.of(new TurnAttachmentDTO(old.id(),"data.txt",old.mediaType(),old.sizeBytes(),old.sha256(),"data.txt")));
        command.setMessage("分析这个文件");
        var prepared=service.prepareInput(command,new AttachmentPreparation());
        assertTrue(prepared.message().startsWith("分析这个文件\n\n附件引用："));
        assertTrue(prepared.message().contains("data.txt"));assertFalse(prepared.message().contains("manifest"));
        assertEquals("分析这个文件",command.getMessage());assertEquals(0,downloads.get());
        Files.writeString(workspace.resolve("data.txt"),"changed");
        assertThrows(AgentOperationException.class,() -> service.prepareInput(command,new AttachmentPreparation()));
    }
}
