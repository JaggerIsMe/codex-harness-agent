package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.entity.dto.ModelRuntimeDTO;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ImageGenerationTransportTest {
    @TempDir Path data;
    @TempDir Path workspace;
    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"/images/generations", "/images/edits"})
    void localCodexImagesReachTheirOriginalUpstreamWithNativeAuthentication(String route) throws Exception {
        var body = new AtomicReference<byte[]>();
        var auth = new AtomicReference<String>();
        var account = new AtomicReference<String>();
        var contentType = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] reply = "{\"data\":[{\"b64_json\":\"aW1hZ2UtZml4dHVyZQ==\"}],\"output\":[{\"type\":\"reasoning\",\"encrypted_content\":\"not-model-history\"}]}".getBytes(StandardCharsets.UTF_8);
        server.createContext("/codex" + route, exchange -> {
            body.set(exchange.getRequestBody().readAllBytes());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            account.set(exchange.getRequestHeaders().getFirst("ChatGPT-Account-ID"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        try (var bridge = new ResponsesCompatibilityProxy(data, workspace,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/codex", "local", json, true, true);
             var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            bridge.bind(UUID.randomUUID().toString());
            // Deliberately opaque tool data: the Responses projector would reject this input.
            byte[] input = "{ \"prompt\": \"image fixture\", \"images\": [], \"input\": [{\"type\":\"image-tool-data\"}] }".getBytes(StandardCharsets.UTF_8);
            for (boolean compressed : List.of(false, true)) {
                var request = request(bridge.baseUrl() + route, input).header("ChatGPT-Account-ID", "synthetic-account");
                if (compressed) request.header("Content-Encoding", "zstd")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(com.github.luben.zstd.Zstd.compress(input)));
                var response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200, response.statusCode(), "Local Codex image generation must not fail at the compatibility bridge: "
                        + new String(response.body(), StandardCharsets.UTF_8));
                assertArrayEquals(input, body.get());
                assertArrayEquals(reply, response.body());
                assertEquals("Bearer synthetic-native-token", auth.get());
                assertEquals("synthetic-account", account.get());
                assertEquals("application/json", contentType.get());
            }
            if ("/images/edits".equals(route)) {
                byte[] multipart = "--fixture\r\nContent-Disposition: form-data; name=\"image\"; filename=\"image.png\"\r\nContent-Type: image/png\r\n\r\n\u0000\u0001\u00ff\r\n--fixture--\r\n".getBytes(StandardCharsets.ISO_8859_1);
                var response = client.send(request(bridge.baseUrl() + route, multipart)
                        .setHeader("Content-Type", "multipart/form-data; boundary=fixture").build(), HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200, response.statusCode());
                assertArrayEquals(multipart, body.get());
                assertEquals("multipart/form-data; boundary=fixture", contentType.get());
                assertArrayEquals(reply, response.body());
            }
            var policy = (ResponsesHistoryPolicy) org.springframework.test.util.ReflectionTestUtils.getField(bridge, "policy");
            assertEquals(1, policy.project(json.readTree("{\"input\":[{\"type\":\"reasoning\",\"encrypted_content\":\"not-model-history\"}]}")).excludedReasoning(),
                    "Image results must not establish model history provenance");
            assertEquals(0, bridge.requestCount(), "Image traffic must not be counted as model inference");
            assertTrue(bridge.diagnostics().contains("imageForwarded=" + ("/images/edits".equals(route) ? 3 : 2) + ", imageStatus=200"));
        } finally {
            server.stop(0);
        }
    }

    @Test void imageRoutesPreserveBindingAndTransportBoundaries() throws Exception {
        var calls = new AtomicInteger();
        var status = new AtomicInteger(200);
        byte[] reply = "{\"error\":{\"message\":\"fixture upstream result\"}}".getBytes(StandardCharsets.UTF_8);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (status.get() == 307) exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/forbidden");
            exchange.sendResponseHeaders(status.get(), reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        String upstream = "http://127.0.0.1:" + server.getAddress().getPort();
        try (var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.ALWAYS).build()) {
            for (boolean search : List.of(false, true)) {
                try (var bridge = new ResponsesCompatibilityProxy(data, workspace, upstream, "managed", json, search)) {
                    bridge.bind(UUID.randomUUID().toString());
                    for (String route : List.of("/images/generations", "/images/edits"))
                        assertEquals(404, client.send(request(bridge.baseUrl() + route, reply).build(), HttpResponse.BodyHandlers.ofByteArray()).statusCode());
                }
            }
            try (var bridge = new ResponsesCompatibilityProxy(data, workspace, upstream, "local", json, false, true)) {
                String url = bridge.baseUrl() + "/images/generations";
                assertEquals(400, client.send(request(url, reply).build(), HttpResponse.BodyHandlers.ofByteArray()).statusCode());
                bridge.bind(UUID.randomUUID().toString());
                for (String route : List.of("/images/generations/", "/images/edits/", "/images/variations", "/images%2fgenerations",
                        "/images/../responses", "/images/generations?target=https://example.com", "/codex/images/generations", "/alpha/search"))
                    assertEquals(404, client.send(request(bridge.baseUrl() + route, reply).build(), HttpResponse.BodyHandlers.ofByteArray()).statusCode());
                assertEquals(404, client.send(request(url, reply).GET().build(), HttpResponse.BodyHandlers.ofByteArray()).statusCode());
                assertEquals(404, client.send(request(url, reply).header("Origin", "https://example.com").build(), HttpResponse.BodyHandlers.ofByteArray()).statusCode());
                assertEquals(0, calls.get(), "Rejected calls must not reach the upstream");
                for (int error : List.of(401, 429, 500)) {
                    status.set(error);
                    var response = client.send(request(url, reply).build(), HttpResponse.BodyHandlers.ofByteArray());
                    assertEquals(error, response.statusCode());
                    assertArrayEquals(reply, response.body());
                }
                status.set(307);
                var response = client.send(request(url, reply).build(), HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(400, response.statusCode());
                assertTrue(response.headers().firstValue("Location").isEmpty());
                assertEquals(4, calls.get(), "Neither the bridge nor its caller may follow an upstream redirect");
                assertFalse(bridge.diagnostics().contains("synthetic-native-token"));
            }
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "custom", "harness_managed"})
    void resumedRuntimeEnablesImagesOnlyForLocalBuiltInOpenAi(String provider) throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/codex/images/generations", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] reply = "{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        String upstream = "http://127.0.0.1:" + server.getAddress().getPort() + "/codex";
        String thread = UUID.randomUUID().toString();
        var base = new AtomicReference<String>();
        var properties = new AgentProperties();
        properties.setDataDir(data);
        properties.setResponsesHistoryCompatibility(true);
        try (var adapter = new AppServerCodexAdapter(properties, json) {
            @Override JsonNode request(String method, JsonNode input) {
                var result = json.createObjectNode();
                switch (method) {
                    case "config/read" -> {
                        var config = result.putObject("config").put("model_provider", provider).put("model", "fixture-model").put("openai_base_url", upstream);
                        config.putObject("model_providers").putObject(provider).put("name", "fixture").put("base_url", upstream)
                                .put("supports_standalone_web_search", true);
                    }
                    case "model/list" -> result.putArray("data").addObject().put("model", "fixture-model").put("isDefault", true);
                    case "account/read" -> result.putObject("account").put("type", "chatgpt");
                    case "thread/read" -> result.putObject("thread").put("id", thread).put("cwd", workspace.toString()).put("modelProvider", "harness_managed");
                    case "thread/resume" -> {
                        assertEquals(thread, input.path("threadId").asText());
                        assertEquals(provider, input.path("modelProvider").asText());
                        base.set("openai".equals(provider) ? input.path("config").path("openai_base_url").asText()
                                : input.path("config").path("model_providers").path(provider).path("base_url").asText());
                        result.putObject("activePermissionProfile").put("id", input.path("permissions").asText());
                        result.put("model", "fixture-model").put("modelProvider", provider);
                        result.putObject("thread").put("id", thread).put("cwd", workspace.toString()).put("modelProvider", "harness_managed");
                    }
                    default -> throw new AssertionError("Unexpected RPC: " + method);
                }
                return result;
            }
        }; var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            var runtime = new ModelRuntimeDTO();
            runtime.setSchemaVersion(2);
            runtime.setRuntimeKey("a".repeat(64));
            runtime.setRuntimeMode("harness_managed".equals(provider) ? "MANAGED_PROVIDER" : "LOCAL_CODEX");
            if ("harness_managed".equals(provider)) {
                runtime.setBaseUrl(upstream);
                runtime.setModelId("fixture-model");
                runtime.setApiKey("synthetic-managed-token");
            }
            adapter.resumeThread(thread, new CodexThreadOptions("project", workspace, runtime));
            var response = client.send(request(base.get() + "/images/generations", "{}".getBytes(StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals("openai".equals(provider) ? 200 : 404, response.statusCode());
            assertEquals("openai".equals(provider) ? 1 : 0, calls.get());
        } finally {
            server.stop(0);
        }
    }

    private HttpRequest.Builder request(String url, byte[] body) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .header("Authorization", "Bearer synthetic-native-token")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    }
}
