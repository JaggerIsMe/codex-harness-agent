package com.myharness.agent.usage;
import com.fasterxml.jackson.databind.*;
import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.security.DeviceIdentityProvider;
import com.myharness.agent.entity.vo.DeviceIdentityVO;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagedUsageClientTest {
    @TempDir Path data;
    @Test void durableUsageSurvivesFailedDeliveryAndRestartWithoutResendingProviderRequest() throws Exception {
        var json=new ObjectMapper();var delivered=new CountDownLatch(1);var offline=new AtomicBoolean(true);var reservations=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1/agent/turns/7/usage/",e->{
            JsonNode body=json.readTree(e.getRequestBody());
            boolean reserve=e.getRequestURI().getPath().endsWith("/reserve");
            if(reserve)reservations.incrementAndGet();
            int status=!reserve&&offline.get()?503:200;
            String response=reserve?json.createObjectNode().put("status","success").set("data",json.createObjectNode().put("requestId",body.path("requestId").asText()).put("maxOutputTokens",100)).toString()
                :status==200?"{\"status\":\"success\",\"data\":null}":"{\"status\":\"error\"}";
            byte[] bytes=response.getBytes(java.nio.charset.StandardCharsets.UTF_8);e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);e.close();
            if(!reserve && status==200){assertEquals(12,body.path("inputTokens").asInt());delivered.countDown();}
        });server.start();
        var props=new AgentProperties();props.setDataDir(data);props.setServerUrl(URI.create("ws://127.0.0.1:"+server.getAddress().getPort()+"/ws/agent"));
        var identity=mock(DeviceIdentityProvider.class);when(identity.get()).thenReturn(new DeviceIdentityVO("test-device","synthetic-device-token"));
        try {
            try(var client=new ClientScope(new ManagedUsageClient(props,identity,json))) {
                var request=client.value.begin("7",json.createObjectNode().put("input","must-not-persist"),false);
                request.observe(json.readTree("{\"type\":\"response.created\",\"response\":{\"status\":\"in_progress\",\"usage\":{\"input_tokens\":0}}}"));
                try(var files=Files.list(data.resolve("managed-usage-outbox"))){assertTrue(files.noneMatch(p->p.toString().endsWith(".json")));}
                request.observe(json.readTree("{\"type\":\"response.completed\",\"response\":{\"id\":\"r\",\"model\":\"fixture\",\"usage\":{\"input_tokens\":12,\"output_tokens\":3,\"input_tokens_details\":{\"cached_tokens\":2}}}}"));
                request.finish();
                try(var files=Files.list(data.resolve("managed-usage-outbox"))) {
                    var pending=files.filter(p->p.toString().endsWith(".json")).toList();assertEquals(1,pending.size());
                    String stored=Files.readString(pending.getFirst());assertFalse(stored.contains("must-not-persist"));assertFalse(stored.contains("synthetic-device-token"));
                }
            }
            offline.set(false);
            try(var restarted=new ClientScope(new ManagedUsageClient(props,identity,json))){assertTrue(delivered.await(20,TimeUnit.SECONDS));}
            assertEquals(1,reservations.get(),"Restart only retries settlement, never reserves or replays inference");
        } finally {server.stop(0);}
    }
    private record ClientScope(ManagedUsageClient value) implements AutoCloseable {public void close(){value.close();}}
}
