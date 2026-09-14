package com.myharness.agent.codex;
import com.fasterxml.jackson.databind.*;
import com.myharness.agent.usage.ManagedUsageClient;
import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.security.DeviceIdentityProvider;
import com.myharness.agent.entity.vo.DeviceIdentityVO;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagedUsageProxyTest {
    @TempDir Path data;
    @TempDir Path workspace;
    ObjectMapper json=new ObjectMapper();
    HttpServer platform,provider;
    ManagedUsageClient usage;
    ResponsesCompatibilityProxy proxy;
    HttpClient http;
    AtomicInteger upstreamCalls=new AtomicInteger(),reservations=new AtomicInteger();
    AtomicReference<JsonNode> reported=new AtomicReference<>(),upstreamBody=new AtomicReference<>();
    AtomicReference<String> denied=new AtomicReference<>();
    CountDownLatch settled=new CountDownLatch(1);
    boolean reject,missing;
    @BeforeEach void setup() throws Exception {
        platform=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        platform.createContext("/api/v1/agent/turns/2/usage/",e->{
            assertEquals("Bearer device-test",e.getRequestHeaders().getFirst("Authorization"));
            JsonNode body=json.readTree(e.getRequestBody());
            if(e.getRequestURI().getPath().endsWith("/reserve")) {
                reservations.incrementAndGet();
                if(reject){reply(e,409,"{\"status\":\"error\",\"info\":\"用户预算不足\"}");return;}
                reply(e,200,json.createObjectNode().put("status","success").set("data",json.createObjectNode().put("requestId",body.path("requestId").asText()).put("maxOutputTokens",500)).toString());
            } else {reported.set(body);reply(e,200,"{\"status\":\"success\",\"data\":null}");settled.countDown();}
        });platform.start();
        provider=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        provider.createContext("/responses",e->{
            upstreamCalls.incrementAndGet();upstreamBody.set(json.readTree(e.getRequestBody()));
            assertEquals("Bearer provider-test",e.getRequestHeaders().getFirst("Authorization"));
            var response=json.createObjectNode().put("id","response-test").put("model","fixture").put("status","completed");
            var message=response.putArray("output").addObject().put("type","message").put("id","message-test").put("role","assistant").put("status","completed");
            message.putArray("content").addObject().put("type","output_text").put("text","USAGE_OK").putArray("annotations");
            if(!missing){var u=response.putObject("usage");u.put("input_tokens",1000).put("output_tokens",100).put("total_tokens",1100);u.putObject("input_tokens_details").put("cached_tokens",500);u.putObject("output_tokens_details").put("reasoning_tokens",40);}
            String event="data: "+json.createObjectNode().put("type","response.output_item.done").put("output_index",0).set("item",message)+"\n\n"
                +"data: "+json.createObjectNode().put("type","response.completed").set("response",response)+"\n\n";
            e.getResponseHeaders().set("Content-Type","text/event-stream");reply(e,200,event);
        });provider.start();
        AgentProperties properties=new AgentProperties();properties.setDataDir(data);properties.setEnrollmentUrl(URI.create("http://127.0.0.1:"+platform.getAddress().getPort()));
        var identity=mock(DeviceIdentityProvider.class);when(identity.get()).thenReturn(new DeviceIdentityVO("device","device-test"));
        usage=new ManagedUsageClient(properties,identity,json);
        proxy=new ResponsesCompatibilityProxy(data,workspace,"http://127.0.0.1:"+provider.getAddress().getPort(),"fixture",json);
        proxy.bind(UUID.randomUUID().toString());proxy.meter(usage,"2",denied::set);
        http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }
    @AfterEach void close(){if(proxy!=null)proxy.close();if(usage!=null)usage.close();if(http!=null)http.close();if(platform!=null)platform.stop(0);if(provider!=null)provider.stop(0);}
    void reply(HttpExchange e,int status,String text) throws java.io.IOException {byte[] b=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);e.sendResponseHeaders(status,b.length);e.getResponseBody().write(b);e.close();}
    HttpResponse<String> request(String extra) throws Exception {
        String body="{\"input\":[{\"role\":\"user\",\"content\":\"private prompt\"}],\"stream\":true,\"max_output_tokens\":900"+extra+"}";
        return http.send(HttpRequest.newBuilder(URI.create(proxy.baseUrl()+"/responses")).header("Authorization","Bearer provider-test").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void reservesBeforeForwardingCapsOutputAndSettlesUsageWithoutContentOrSecrets() throws Exception {
        assertEquals(200,request("").statusCode());assertTrue(settled.await(5,TimeUnit.SECONDS));
        assertEquals(1,reservations.get());assertEquals(1,upstreamCalls.get());assertEquals(500,upstreamBody.get().path("max_output_tokens").asInt());
        assertEquals(1000,reported.get().path("inputTokens").asInt());assertEquals(500,reported.get().path("cachedTokens").asInt());assertEquals(100,reported.get().path("outputTokens").asInt());
        assertFalse(reported.get().toString().contains("private prompt"));assertFalse(reported.get().toString().contains("provider-test"));
    }
    @Test void exhaustedBudgetStopsRequestBeforeProviderAndNotifiesTurn() throws Exception {
        reject=true;assertEquals(400,request("").statusCode());assertEquals(0,upstreamCalls.get());assertEquals("用户预算不足",denied.get());
    }
    @Test void missingUsageIsReportedAsUnknownNotZero() throws Exception {
        missing=true;assertEquals(200,request("").statusCode());assertTrue(settled.await(5,TimeUnit.SECONDS));
        assertFalse(reported.get().has("inputTokens"));assertEquals("INTERRUPTED",reported.get().path("outcome").asText());
    }
    @Test void unsupportedPaidToolsNeverReachProvider() throws Exception {
        var response=request(",\"tools\":[{\"type\":\"web_search\"}]");
        assertEquals(400,response.statusCode());
        assertTrue(response.body().contains("当前计价规则不支持"));
        assertTrue(response.body().contains("web_search"));
        assertEquals("managed_usage_error",json.readTree(response.body()).path("error").path("type").asText());
        assertEquals(0,upstreamCalls.get());assertEquals(0,reservations.get());
    }
    @Test void namespaceContainersPreserveOrdinaryToolsAndStillSettleUsage() throws Exception {
        String tools="""
            [{"type":"namespace","name":"functions","description":"Fixture tools","tools":[
              {"type":"function","name":"lookup","parameters":{"type":"object","properties":{}}},
              {"type":"namespace","name":"editing","tools":[{"type":"custom","name":"apply_patch"}]}
            ]}]
            """;
        var response=request(",\"tools\":"+tools);
        assertEquals(200,response.statusCode(),response.body());
        assertTrue(settled.await(5,TimeUnit.SECONDS));
        assertEquals(json.readTree(tools),upstreamBody.get().path("tools"));
        assertEquals(1,reservations.get());assertEquals(1,upstreamCalls.get());
        assertEquals(100,reported.get().path("outputTokens").asInt());
    }
    @Test void namespaceCannotHideUnsupportedTools() throws Exception {
        var response=request(",\"tools\":[{\"type\":\"namespace\",\"name\":\"outer\",\"tools\":[{\"type\":\"namespace\",\"name\":\"inner\",\"tools\":[{\"type\":\"web_search\"}]}]}]");
        assertEquals(400,response.statusCode());
        assertEquals("managed_usage_error",json.readTree(response.body()).path("error").path("type").asText());
        assertTrue(json.readTree(response.body()).path("error").path("message").asText().contains("web_search"));
        assertEquals(0,reservations.get());assertEquals(0,upstreamCalls.get());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={
        "{\"type\":\"namespace\",\"name\":\"invalid\"}",
        "{\"type\":\"namespace\",\"name\":\"invalid\",\"tools\":null}",
        "{\"type\":\"namespace\",\"name\":\"invalid\",\"tools\":{\"type\":\"function\",\"name\":\"f\"}}",
        "{\"type\":\"namespace\",\"name\":\"invalid\",\"tools\":[null]}"
    })
    void malformedNamespaceCannotReachBudgetOrProvider(String tool) throws Exception {
        var response=request(",\"tools\":["+tool+"]");
        assertEquals(400,response.statusCode());
        assertEquals("managed_usage_error",json.readTree(response.body()).path("error").path("type").asText());
        assertEquals(0,reservations.get());assertEquals(0,upstreamCalls.get());
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="codex.usage.smoke",matches="true")
    void realCodexManagedQuestionCompletesAndResumesWithoutUnsupportedToolDeclarations() throws Exception {
        var properties=new AgentProperties();properties.setDataDir(data);
        if(System.getProperty("os.name").startsWith("Windows")) {
            org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("windows.isolation.python")!=null);
            properties.setWindowsPython(Path.of(System.getProperty("windows.isolation.python")));
        }
        var runtime=new com.myharness.agent.entity.dto.ModelRuntimeDTO();runtime.setSchemaVersion(3);
        runtime.setRuntimeMode("MANAGED_PROVIDER");runtime.setRuntimeKey("a".repeat(64));
        runtime.setConfigurationVersionId(1L);runtime.setModelId("fixture");runtime.setProviderName("Fixture");
        runtime.setApiKey("provider-test");runtime.setBaseUrl("http://127.0.0.1:"+provider.getAddress().getPort());
        var options=new CodexThreadOptions("fixture-project",workspace,runtime).withExpertRuntime(List.of(),List.of());
        String thread=null;
        try {for(int attempt=0;attempt<2;attempt++) {
            try(var adapter=new AppServerCodexAdapter(properties,json).withUsageClient(usage)) {
                if(thread==null)thread=adapter.startThread(options);else adapter.resumeThread(thread,options);
                var result=new CompletableFuture<String>();
                adapter.startTurn(thread,new CodexTurnInput("Reply USAGE_OK. Do not use tools.").withHarnessTurnId("2").withManagedUsage(true),new CodexEventListener() {
                    public void onEvent(CodexEvent event) {}
                    public void onApproval(CodexApproval approval) {result.completeExceptionally(new AssertionError("Unexpected approval"));}
                    public void onCompleted(String tid,String status,String reason) {result.complete("completed".equals(status)?status:reason);}
                });
                assertEquals("completed",result.get(30,TimeUnit.SECONDS));
                assertTrue(settled.await(5,TimeUnit.SECONDS));
                assertFalse(upstreamBody.get().path("tools").toString().contains("\"type\":\"web_search\""));
                assertEquals(500,upstreamBody.get().path("max_output_tokens").asInt());
            }
        }
        assertEquals(2,upstreamCalls.get());assertEquals(2,reservations.get());
        } finally {
            if(System.getProperty("os.name").startsWith("Windows"))WindowsIsolatedCommand.cleanupProfile(workspace,properties.getWindowsPython());
        }
    }
}
