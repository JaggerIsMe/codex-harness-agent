package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ResponsesCompatibilityProxyTest {
    @TempDir Path data;
    @TempDir Path workspace;
    private final ObjectMapper json=new ObjectMapper();
    @Test void standaloneSearchBypassesHistoryProjectionButNotTransportBoundaries() throws Exception {
        var requests=new ArrayList<String>();var paths=new ArrayList<String>();var auth=new ArrayList<String>();
        var status=new AtomicInteger(200);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/codex/alpha/search",exchange->{
            paths.add(exchange.getRequestURI().getRawPath());
            requests.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] reply="{\"result\":\"fixture search result\",\"output\":[{\"type\":\"reasoning\",\"encrypted_content\":\"not-model-history\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            if(status.get()==307) exchange.getResponseHeaders().set("Location","https://example.com/never");
            exchange.sendResponseHeaders(status.get(),reply.length);exchange.getResponseBody().write(reply);exchange.close();
        });server.start();
        String upstream="http://127.0.0.1:"+server.getAddress().getPort()+"/codex";
        try(var bridge=new ResponsesCompatibilityProxy(data,workspace,upstream,"a",json,true);
            var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            String body="{\"search_query\":[{\"q\":\"fixture\"}],\"input\":[{\"type\":\"unknown-tool-data\"}]}";
            assertEquals(400,client.send(request(bridge.baseUrl()+"/alpha/search",body),HttpResponse.BodyHandlers.ofString()).statusCode());
            assertTrue(requests.isEmpty(),"Unbound tool calls must not reach upstream");
            bridge.bind(UUID.randomUUID().toString());
            for(boolean compressed:List.of(false,true)) {
                var req=compressed?HttpRequest.newBuilder(URI.create(bridge.baseUrl()+"/alpha/search")).header("Content-Encoding","zstd")
                        .header("Authorization","Bearer synthetic-test-token").POST(HttpRequest.BodyPublishers.ofByteArray(com.github.luben.zstd.Zstd.compress(body.getBytes(StandardCharsets.UTF_8)))).build()
                        :request(bridge.baseUrl()+"/alpha/search",body);
                var response=client.send(req,HttpResponse.BodyHandlers.ofString());
                assertEquals(200,response.statusCode());assertTrue(response.body().contains("fixture search result"));
                assertEquals(body,requests.getLast());assertEquals("Bearer synthetic-test-token",auth.getLast());
                assertEquals("/codex/alpha/search",paths.getLast());
            }
            var policy=(ResponsesHistoryPolicy)org.springframework.test.util.ReflectionTestUtils.getField(bridge,"policy");
            assertEquals(1,policy.project(json.readTree("{\"input\":[{\"type\":\"reasoning\",\"encrypted_content\":\"not-model-history\"}]}" )).excludedReasoning(),"Search output must never establish reasoning provenance");
            for(String suffix:List.of("/alpha/search/","/codex/alpha/search","/alpha/search?target=https://example.com","/alpha/../responses","/alpha%2fsearch","/other"))
                assertEquals(404,client.send(request(bridge.baseUrl()+suffix,body),HttpResponse.BodyHandlers.ofString()).statusCode());
            var get=HttpRequest.newBuilder(URI.create(bridge.baseUrl()+"/alpha/search")).GET().build();
            assertEquals(404,client.send(get,HttpResponse.BodyHandlers.ofString()).statusCode());
            var origin=HttpRequest.newBuilder(URI.create(bridge.baseUrl()+"/alpha/search")).header("Origin","https://example.com").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            assertEquals(404,client.send(origin,HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(2,requests.size());
            assertEquals(0,bridge.requestCount(),"Standalone tool traffic must not count as model inference");
            assertTrue(bridge.diagnostics().contains("searchForwarded=2, searchStatus=200"));
            status.set(429);assertEquals(429,client.send(request(bridge.baseUrl()+"/alpha/search",body),HttpResponse.BodyHandlers.ofString()).statusCode());
            status.set(307);var redirect=client.send(request(bridge.baseUrl()+"/alpha/search",body),HttpResponse.BodyHandlers.ofString());
            assertEquals(400,redirect.statusCode());assertTrue(redirect.headers().firstValue("Location").isEmpty());assertEquals(4,requests.size());
        } finally {server.stop(0);}
        try(var bridge=new ResponsesCompatibilityProxy(data,workspace,upstream,"b",json);var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            bridge.bind(UUID.randomUUID().toString());
            assertEquals(404,client.send(request(bridge.baseUrl()+"/alpha/search","{}"),HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
    @Test void websocketUpgradeExplicitlyRequestsHttpWithoutForwardingIt() throws Exception {
        try(var bridge=new ResponsesCompatibilityProxy(data,workspace,"https://example.com/v1","a",json);
            var client=HttpClient.newHttpClient()) {
            bridge.bind(UUID.randomUUID().toString());
            var failure=assertThrows(java.util.concurrent.CompletionException.class,()->client.newWebSocketBuilder()
                    .buildAsync(URI.create(bridge.baseUrl().replace("http://","ws://")+"/responses"),new WebSocket.Listener() {}).join());
            assertInstanceOf(WebSocketHandshakeException.class,failure.getCause());
            assertEquals(426,((WebSocketHandshakeException)failure.getCause()).getResponse().statusCode());
            assertEquals(0,bridge.requestCount());
        }
    }
    @Test void refusesUnsafeUpstreamAddresses() {
        for(String url:List.of("http://example.com/v1","https://user:secret@example.com/v1","https://example.com/v1?key=secret","https://example.com/v1#fragment")) {
            assertThrows(CodexException.class,()->new ResponsesCompatibilityProxy(data,workspace,url,"a",json));
        }
    }
    @Test void neverForwardsHttp2PseudoHeadersOrHopHeaders() {
        for(String key:List.of(":status",":authority","Content-Length","Transfer-Encoding","Connection","Content-Encoding")) assertFalse(ResponsesCompatibilityProxy.forwardHeader(key));
        for(String key:List.of("Content-Type","Authorization","X-Request-Id")) assertTrue(ResponsesCompatibilityProxy.forwardHeader(key));
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void projectsRequestsStreamsResponsesAndForwardsOnlyToTheFixedUpstream(boolean contentTypePresent) throws Exception {
        var body=new AtomicReference<String>();var authorization=new AtomicReference<String>();var count=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String sse="event: response.output_item.done\ndata: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"reasoning\",\"encrypted_content\":\"native-state\",\"summary\":[]}}\n\n";
        server.createContext("/v1/responses",exchange->{
            count.incrementAndGet();body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes=sse.getBytes(StandardCharsets.UTF_8);if(contentTypePresent) exchange.getResponseHeaders().set("Content-Type","text/event-stream");
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        var notices=new ArrayList<String>();String id=UUID.randomUUID().toString();
        try(var bridge=new ResponsesCompatibilityProxy(data,workspace,"http://127.0.0.1:"+server.getAddress().getPort()+"/v1","a",json);
            var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            bridge.bind(id);bridge.onNotice(notices::add);
            String input="{\"stream\":true,\"input\":[{\"type\":\"reasoning\",\"encrypted_content\":\"foreign-state\",\"summary\":[]},{\"type\":\"message\",\"role\":\"user\",\"content\":\"hello\"}]}";
            var response=client.send(request(bridge.baseUrl()+"/responses",input),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());assertEquals(sse,response.body());
            assertFalse(body.get().contains("foreign-state"));assertTrue(body.get().contains("hello"));
            assertEquals("Bearer synthetic-test-token",authorization.get());assertEquals(1,notices.size());
            client.send(request(bridge.baseUrl()+"/responses",input.replace("foreign-state","native-state")),HttpResponse.BodyHandlers.ofString());
            assertTrue(body.get().contains("native-state"));assertEquals(1,notices.size());
            byte[] compressed=com.github.luben.zstd.Zstd.compress(input.getBytes(StandardCharsets.UTF_8));
            var compressedRequest=HttpRequest.newBuilder(URI.create(bridge.baseUrl()+"/responses")).header("Content-Encoding","zstd")
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(compressed)).build();
            assertEquals(200,client.send(compressedRequest,HttpResponse.BodyHandlers.ofString()).statusCode());
            assertFalse(body.get().contains("foreign-state"));
            for(String suffix:List.of("/unknown","/../responses","/responses?redirect=https://example.com")) {
                assertEquals(404,client.send(request(bridge.baseUrl()+suffix,"{\"input\":[]}"),HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            assertEquals(3,count.get());assertThrows(CodexException.class,()->bridge.verifyIdentity("b"));
            assertThrows(CodexException.class,()->bridge.bind(UUID.randomUUID().toString()));
        } finally {server.stop(0);}
        try(var policy=new ResponsesHistoryPolicy(data,workspace,id,"a",json)) {assertNotNull(policy);}
    }
    @Test void invalidHistoryAndRedirectNeverProduceAnotherProviderRequest() throws Exception {
        var calls=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/responses",exchange->{
            calls.incrementAndGet();exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/forbidden");exchange.sendResponseHeaders(307,-1);exchange.close();
        });server.createContext("/forbidden",exchange->{calls.addAndGet(100);exchange.sendResponseHeaders(500,-1);exchange.close();});server.start();
        try(var bridge=new ResponsesCompatibilityProxy(data,workspace,"http://127.0.0.1:"+server.getAddress().getPort(),"a",json);
            var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.ALWAYS).build()) {
            bridge.bind(UUID.randomUUID().toString());
            assertEquals(400,client.send(request(bridge.baseUrl()+"/responses","{\"input\":[{\"type\":\"compaction\",\"encrypted_content\":\"foreign\"}]}"),HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(0,calls.get());
            var response=client.send(request(bridge.baseUrl()+"/responses","{\"input\":[]}"),HttpResponse.BodyHandlers.ofString());
            assertEquals(400,response.statusCode());assertFalse(response.headers().firstValue("Location").isPresent());assertEquals(1,calls.get());
        } finally {server.stop(0);}
    }
    private HttpRequest request(String url,String body) {
        return HttpRequest.newBuilder(URI.create(url)).header("Content-Type","application/json").header("Authorization","Bearer synthetic-test-token")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }
}
