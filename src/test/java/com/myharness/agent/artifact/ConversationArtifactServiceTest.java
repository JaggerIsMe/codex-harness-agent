package com.myharness.agent.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.agent.command.AgentOperationException;
import com.myharness.agent.config.*;
import com.myharness.agent.entity.vo.DeviceIdentityVO;
import com.myharness.agent.security.DeviceIdentityProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationArtifactServiceTest {
    @TempDir Path temp;
    Path workspace;
    AgentProperties agent;
    ArtifactProperties limits;
    DeviceIdentityProvider identity;
    HttpServer server;
    ObjectMapper json=new ObjectMapper();
    List<ConversationArtifactService> services=new ArrayList<>();
    Map<String,com.fasterxml.jackson.databind.node.ObjectNode> records=new ConcurrentHashMap<>();
    Map<String,byte[]> contents=new ConcurrentHashMap<>();
    AtomicInteger uploads=new AtomicInteger();
    volatile boolean offline;
    volatile boolean failUpload;
    volatile int registrationStatus;
    ConversationArtifactService service;

    @BeforeEach void setup() throws Exception {
        workspace=Files.createDirectory(temp.resolve("workspace"));
        agent=new AgentProperties();agent.setDataDir(temp.resolve("agent-data"));
        limits=new ArtifactProperties();
        identity=mock(DeviceIdentityProvider.class);when(identity.get()).thenReturn(new DeviceIdentityVO("device","token"));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        agent.setEnrollmentUrl(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/api/v1/agent/enroll"));
        server.createContext("/api/v1/agent/turns/",exchange -> {
            try {
                assertEquals("Bearer token",exchange.getRequestHeaders().getFirst("Authorization"));
                assertEquals("device",exchange.getRequestHeaders().getFirst("X-Harness-Device-Code"));
                var data=json.createObjectNode();String path=exchange.getRequestURI().getPath();
                int status=200;
                if(registrationStatus!=0) status=registrationStatus;
                else if(offline) status=503;
                else if(path.endsWith("/artifacts")) {
                    var input=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(exchange.getRequestBody());
                    String key=input.path("artifactKey").asText();
                    data=records.computeIfAbsent(key,k -> input.put("id",String.valueOf(records.size()+1)).put("status","UPLOADING"));
                } else {
                    String[] segments=path.split("/");String id=segments[7];
                    data=records.values().stream().filter(r -> id.equals(r.path("id").asText())).findFirst().orElseThrow();
                    if(path.endsWith("/content")) {
                        uploads.incrementAndGet();
                        byte[] bytes=exchange.getRequestBody().readAllBytes();
                        if(failUpload) status=503;
                        else {contents.put(id,bytes);data.put("status","READY");}
                    } else if(path.endsWith("/failed") && !"READY".equals(data.path("status").asText())) data.put("status","FAILED");
                }
                byte[] response=json.writeValueAsBytes(Map.of("status",status==200?"success":"error","code",status,
                        "info",status==200?"success":"Authorization: Bearer fixture-sensitive-response","data",data));
                exchange.sendResponseHeaders(status,response.length);
                exchange.getResponseBody().write(response);
            } finally {exchange.close();}
        });
        server.start();service=create();
    }
    ConversationArtifactService create() {
        var value=new ConversationArtifactService(agent,limits,identity,json);services.add(value);return value;
    }
    @AfterEach void close(){services.forEach(ConversationArtifactService::close);server.stop(0);}
    Path output(String tid) throws Exception {return Files.createDirectories(workspace.resolve(".harness/outputs/"+tid));}
    void delivery(String tid,String text) throws Exception {
        Path output=output(tid);Files.writeString(output.resolve("report.txt"),text);
        Files.writeString(output.resolve("manifest.json"),"{\"files\":[{\"path\":\"report.txt\",\"name\":\"报告.txt\"}]}");
    }
    long pending() throws Exception {
        try(var files=Files.list(agent.getDataDir().resolve("artifact-spool"))){return files.filter(p -> p.toString().endsWith(".json")).count();}
    }
    @Test void restartUploadsOriginalSnapshotAfterWorkspaceChangedAndCleansSpool() throws Exception {
        delivery("7","original");
        assertEquals(1,service.capture(workspace,"7"));
        delivery("7","changed after completion");
        service.close();
        create().drain();
        assertEquals("original",new String(contents.get("1"),java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("报告.txt",records.values().iterator().next().path("fileName").asText());
        assertEquals(0,pending());
    }
    @Test void uploadFailureWaitsForUserRetryAndNeverRecapturesWorkspace() throws Exception {
        delivery("7","original");service.capture(workspace,"7");
        failUpload=true;service.drain();
        assertEquals(1,uploads.get());assertEquals(1,pending());
        assertEquals("FAILED",records.values().iterator().next().path("status").asText());
        failUpload=false;service.drain();assertEquals(1,uploads.get());
        records.values().iterator().next().put("status","UPLOADING");
        delivery("7","later");create().drain();
        assertEquals(2,uploads.get());assertEquals("original",new String(contents.get("1")));
        assertEquals(0,pending());
    }
    @Test void registrationOutageKeepsDurableQueueAndReadyResponseDoesNotUploadAgain() throws Exception {
        delivery("7","hello");service.capture(workspace,"7");
        offline=true;service.drain();assertEquals(1,pending());assertEquals(0,uploads.get());
        offline=false;service.drain();assertEquals(1,uploads.get());assertEquals(0,pending());
        service.drain();assertEquals(1,uploads.get());
    }
    @Test void registrationFailureLogsStageAndHttpStatusWithoutLeakingCredentials() throws Exception {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(ConversationArtifactService.class);
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();logger.addAppender(appender);
        try {
            delivery("7","original");service.capture(workspace,"7");
            registrationStatus=401;service.drain();
            String logs=appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(logs.contains("stage=REGISTER"),logs);
            assertTrue(logs.contains("httpStatus=401"),logs);
            assertTrue(logs.contains("turnId=7"),logs);
            assertFalse(logs.contains("fixture-sensitive-response"));
            assertFalse(logs.contains("Bearer"));
            assertEquals(1,pending());assertEquals(0,uploads.get());
            registrationStatus=0;service.drain();
            assertEquals("original",new String(contents.get("1")));assertEquals(0,pending());
        } finally {logger.detachAppender(appender);appender.stop();}
    }
    @Test void laterTurnWithSameFilenameKeepsBothVersions() throws Exception {
        delivery("7","first");service.capture(workspace,"7");service.drain();
        delivery("8","second");service.capture(workspace,"8");service.drain();
        assertEquals(2,records.size());assertEquals("first",new String(contents.get("1")));
        assertEquals("second",new String(contents.get("2")));
    }
    @Test void rejectsTraversalAbsolutePathsAndDuplicateEntriesWithoutQueueing() throws Exception {
        Path output=output("7");Files.writeString(output.resolve("report.txt"),"ok");
        for(String path:List.of("../private.txt","/private.txt","C:/private.txt")) {
            Files.writeString(output.resolve("manifest.json"),json.writeValueAsString(Map.of("files",List.of(Map.of("path",path,"name","a.txt")))));
            assertThrows(AgentOperationException.class,() -> service.capture(workspace,"7"));
        }
        Files.writeString(output.resolve("manifest.json"),"{\"files\":[{\"path\":\"report.txt\"},{\"path\":\"report.txt\"}]}");
        assertThrows(AgentOperationException.class,() -> service.capture(workspace,"7"));
        assertEquals(0,pending());
        try(var files=Files.list(agent.getDataDir().resolve("artifact-spool"))){assertEquals(0,files.count());}
    }
    @Test void rejectsWindowsJunctionOrSymbolicLinkOutsideOutput() throws Exception {
        Path outside=Files.createDirectory(temp.resolve("outside"));Files.writeString(outside.resolve("secret.txt"),"secret");
        Path output=output("7");Path link=output.resolve("linked");
        if(System.getProperty("os.name").toLowerCase().contains("win")) {
            var process=new ProcessBuilder("cmd.exe","/d","/c","mklink","/J",link.toString(),outside.toString()).redirectErrorStream(true).start();
            assertEquals(0,process.waitFor(),new String(process.getInputStream().readAllBytes()));
        } else Files.createSymbolicLink(link,outside);
        try {
            Files.writeString(output.resolve("manifest.json"),"{\"files\":[{\"path\":\"linked/secret.txt\"}]}");
            assertThrows(AgentOperationException.class,() -> service.capture(workspace,"7"));
            assertEquals(0,pending());assertEquals(0,uploads.get());
        } finally {Files.deleteIfExists(link);}
    }
    @Test void enforcesFileTotalAndSpoolLimitsAndAllowsEmptyFiles() throws Exception {
        delivery("7","large");limits.setMaxFileBytes(2);
        assertThrows(AgentOperationException.class,() -> service.capture(workspace,"7"));
        limits.setMaxFileBytes(20);limits.setMaxTotalBytes(2);
        assertThrows(AgentOperationException.class,() -> service.capture(workspace,"7"));
        limits.setMaxTotalBytes(20);limits.setMaxSpoolBytes(2);
        assertThrows(AgentOperationException.class,() -> service.capture(workspace,"7"));
        delivery("7","");assertEquals(1,service.capture(workspace,"7"));service.drain();assertEquals(0,contents.get("1").length);
    }
    @Test void absentManifestProducesNoArtifactAndUnsafeTurnCannotBecomePath() {
        assertEquals(0,service.capture(workspace,"7"));
        assertThrows(AgentOperationException.class,() -> service.instructions("../7"));
        assertTrue(service.instructions("7").contains(".harness/outputs/7/"));
    }
}
