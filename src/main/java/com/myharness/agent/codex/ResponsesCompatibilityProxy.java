package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Private loopback Responses bridge. Never follows redirects, logs credentials, or changes provider. */
final class ResponsesCompatibilityProxy implements AutoCloseable {
    private static final int MAX_BYTES=64*1024*1024;
    private static final Set<String> HOP=Set.of("host","connection","content-length","content-encoding","transfer-encoding","upgrade","keep-alive","proxy-authenticate","proxy-authorization","te","trailer","accept-encoding");
    private final HttpServer server;
    private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(30)).build();
    private final Set<InputStream> streams=ConcurrentHashMap.newKeySet();
    private final ObjectMapper json;
    private final Path data,workspace;
    private final String upstream,identity,prefix="/"+UUID.randomUUID();
    private final boolean standaloneSearch;
    private String thread;
    private volatile ResponsesHistoryPolicy policy;
    private volatile Consumer<String> notice=ignored->{ };
    private final Set<String> notified=ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicLong requests=new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong searchRequests=new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong received=new java.util.concurrent.atomic.AtomicLong();
    private volatile String lastFailure="none";
    private volatile int lastStatus;
    private volatile int lastSearchStatus;
    private volatile String lastMime="none";
    private volatile String lastEvent="none";

    ResponsesCompatibilityProxy(Path data,Path workspace,String upstream,String identity,ObjectMapper json) {
        this(data,workspace,upstream,identity,json,false);
    }
    ResponsesCompatibilityProxy(Path data,Path workspace,String upstream,String identity,ObjectMapper json,boolean standaloneSearch) {
        this.standaloneSearch=standaloneSearch;
        this.data=data;this.workspace=workspace;this.identity=identity;this.json=json;
        try {
            URI url=URI.create(upstream);
            if(url.getHost()==null || url.getUserInfo()!=null || url.getQuery()!=null || url.getFragment()!=null
                    || !(url.getScheme().equals("https") || (url.getScheme().equals("http") && Set.of("127.0.0.1","localhost","::1","[::1]").contains(url.getHost()))))
                throw ResponsesHistoryPolicy.failure("Upstream must be HTTPS (or loopback HTTP for local providers)");
            this.upstream=upstream.replaceAll("/+$","");
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",this::handle);server.setExecutor(executor);server.start();
        } catch(CodexException e) {client.shutdownNow();executor.shutdownNow();throw e;}
        catch(IOException | IllegalArgumentException | NullPointerException e) {client.shutdownNow();executor.shutdownNow();throw ResponsesHistoryPolicy.failure("Cannot start the local Responses compatibility bridge");}
    }
    String baseUrl() {return "http://127.0.0.1:"+server.getAddress().getPort()+prefix;}
    String upstream() {return upstream;}
    long requestCount() {return requests.get();}
    String diagnostics() {return "received="+received.get()+", forwarded="+requests.get()+", searchForwarded="+searchRequests.get()+", searchStatus="+lastSearchStatus+", upstreamStatus="+lastStatus+", mime="+lastMime+", event="+lastEvent+", failure="+lastFailure;}
    void verifyIdentity(String expected) {if(!identity.equals(expected)) throw ResponsesHistoryPolicy.failure("Cannot change an active bridge target");}
    synchronized void bind(String threadId) {
        if(thread!=null) {if(!thread.equals(threadId)) throw ResponsesHistoryPolicy.failure("Bridge is bound to another Conversation");return;}
        policy=new ResponsesHistoryPolicy(data,workspace,threadId,identity,json);thread=threadId;
    }
    void onNotice(Consumer<String> callback) {notice=callback;notified.clear();}
    private void handle(HttpExchange exchange) throws IOException {
        received.incrementAndGet();
        boolean sent=false;
        try {
            String path=exchange.getRequestURI().getRawPath();
            String suffix=path.startsWith(prefix+"/")?path.substring(prefix.length()):"";
            boolean search=standaloneSearch && "/alpha/search".equals(suffix);
            if("GET".equals(exchange.getRequestMethod()) && Set.of("/responses","/codex/responses").contains(suffix)
                    && exchange.getRequestURI().getRawQuery()==null && !exchange.getRequestHeaders().containsKey("Origin")
                    && "websocket".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Upgrade"))) {
                // Codex recognizes 426 as an HTTP-only endpoint; 404 causes repeated reconnects.
                error(exchange,426,"This Responses endpoint requires HTTP POST with SSE, not WebSocket");return;
            }
            if(!"POST".equals(exchange.getRequestMethod()) || (!search && !Set.of("/responses","/responses/compact","/codex/responses","/codex/responses/compact").contains(suffix))
                    || exchange.getRequestURI().getRawQuery()!=null || exchange.getRequestHeaders().containsKey("Origin") || exchange.getRequestHeaders().containsKey("Upgrade")) {
                lastFailure="unsupported route, method or upgrade";error(exchange,404,"Unsupported compatibility bridge route");return;
            }
            if(policy==null) throw ResponsesHistoryPolicy.failure("Conversation is not bound to bridge");
            byte[] compressed=bounded(exchange.getRequestBody());
            byte[] body;boolean requestStream=false;
            try(InputStream decoded=decode(new ByteArrayInputStream(compressed),exchange.getRequestHeaders().getFirst("Content-Encoding"))) {
                body=bounded(decoded);
                // Standalone tools share the provider base URL, but have no Responses history.
                // Preserve their payload bytes; never project or learn provenance from tool results.
                if(!search) {
                    var projection=policy.project(json.readTree(body));
                    if(projection.excludedReasoning()>0 && notified.add("reasoning")) notice.accept("已按 "+ResponsesHistoryPolicy.POLICY+" 兼容当前模型：本次请求未发送 "+projection.excludedReasoning()+" 条其他提供商或来源未验证的思考记录；原始会话历史保持不变。");
                    if(projection.convertedSearches()>0 && notified.add("search")) notice.accept("已按 search-portable-v1 将 "+projection.convertedSearches()+" 条异源或来源未验证的搜索记录转换为带来源标记的历史文本；仅保留已有查询、来源等信息，不补造缺失结果，不重放搜索；原始历史保持不变。");
                    requestStream=projection.request().path("stream").asBoolean(false);
                    body=json.writeValueAsBytes(projection.request());
                }
            }
            if(search) searchRequests.incrementAndGet();else requests.incrementAndGet();
            var builder=HttpRequest.newBuilder(URI.create(upstream+suffix)).timeout(Duration.ofMinutes(5))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            exchange.getRequestHeaders().forEach((key,values)->{if(forwardHeader(key)) values.forEach(value->builder.header(key,value));});
            builder.header("Accept-Encoding","identity");
            HttpResponse<InputStream> response=client.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());
            lastStatus=response.statusCode();
            if(search) lastSearchStatus=response.statusCode();
            lastMime=response.headers().firstValue("content-type").orElse("none").replaceAll("[^a-zA-Z0-9/;= ._-]","");
            try(InputStream rawInput=response.body();InputStream input=decode(rawInput,response.headers().firstValue("content-encoding").orElse(null))) {
                streams.add(rawInput);
                if(response.statusCode()>=300 && response.statusCode()<400) throw ResponsesHistoryPolicy.failure("Provider redirect refused");
                response.headers().map().forEach((key,values)->{if(forwardHeader(key)) exchange.getResponseHeaders().put(key,values);});
                String contentType=response.headers().firstValue("content-type").orElse("");
                boolean sse=!search && (contentType.contains("text/event-stream") || (contentType.isBlank() && requestStream));
                if(sse && response.statusCode()<300) {
                    exchange.getResponseHeaders().set("Content-Type","text/event-stream");
                    exchange.sendResponseHeaders(response.statusCode(),0);sent=true;
                    relaySse(input,exchange.getResponseBody());
                } else {
                    byte[] reply=bounded(input);
                    if(!search && response.statusCode()<300 && lastMime.contains("json")) policy.observe(json.readTree(reply));
                    exchange.sendResponseHeaders(response.statusCode(),reply.length);sent=true;exchange.getResponseBody().write(reply);
                }
            } finally {streams.remove(response.body());}
        } catch(InterruptedException e) {Thread.currentThread().interrupt();if(!sent) error(exchange,502,"Responses request interrupted");}
        catch(Exception e) {
            String message=e instanceof CodexException?e.getMessage():"Responses compatibility bridge failed";
            lastFailure=message+" ("+e.getClass().getSimpleName()+")";
            if(!sent) error(exchange,e instanceof CodexException?400:502,message);
        } finally {exchange.close();}
    }
    private void relaySse(InputStream input,OutputStream output) throws IOException {
        var reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8));
        StringBuilder data=new StringBuilder();String line;
        while((line=readLine(reader))!=null) {
            if(line.isEmpty()) {
                if(!data.isEmpty() && !"[DONE]".equals(data.toString().trim())) {
                    JsonNode event=json.readTree(data.toString());lastEvent=event.path("type").asText().replaceAll("[^a-zA-Z0-9_.]","");policy.observe(event);
                }
                data.setLength(0);
            } else if(line.startsWith("data:")) {
                if(!data.isEmpty()) data.append('\n');data.append(line.substring(5).stripLeading());
                if(data.length()>MAX_BYTES) throw ResponsesHistoryPolicy.failure("Responses event exceeds compatibility limit");
            }
            output.write(line.getBytes(StandardCharsets.UTF_8));output.write('\n');
            if(line.isEmpty()) output.flush();
        }
    }
    private String readLine(Reader input) throws IOException {
        StringBuilder line=new StringBuilder();int c;
        while((c=input.read())!=-1) {if(c=='\n') break;if(c!='\r') line.append((char)c);if(line.length()>MAX_BYTES) throw ResponsesHistoryPolicy.failure("Responses event exceeds compatibility limit");}
        return c==-1 && line.isEmpty()?null:line.toString();
    }
    private byte[] bounded(InputStream input) throws IOException {byte[] value=input.readNBytes(MAX_BYTES+1);if(value.length>MAX_BYTES) throw ResponsesHistoryPolicy.failure("Responses payload exceeds 64 MiB limit");return value;}
    static boolean forwardHeader(String key) {
        // HTTP/2 pseudo headers are not valid HTTP/1.1 response headers.
        return key.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") && !HOP.contains(key.toLowerCase(Locale.ROOT));
    }
    private InputStream decode(InputStream input,String encoding) throws IOException {
        if(encoding==null || encoding.equalsIgnoreCase("identity")) return input;
        return switch(encoding.toLowerCase(Locale.ROOT)) {
            case "zstd" -> new com.github.luben.zstd.ZstdInputStream(input).setLongMax(26);
            case "gzip" -> new java.util.zip.GZIPInputStream(input);
            default -> throw ResponsesHistoryPolicy.failure("Unsupported Responses content encoding");
        };
    }
    private void error(HttpExchange exchange,int status,String message) throws IOException {
        var body=json.createObjectNode();body.putObject("error").put("type","history_compatibility_error").put("message",message);
        byte[] bytes=json.writeValueAsBytes(body);exchange.getResponseHeaders().set("Content-Type","application/json");
        exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);
    }
    @Override public void close() {
        server.stop(0);streams.forEach(stream->{try {stream.close();} catch(IOException ignored) { }});
        executor.shutdownNow();client.shutdownNow();if(policy!=null) policy.close();
    }
}
