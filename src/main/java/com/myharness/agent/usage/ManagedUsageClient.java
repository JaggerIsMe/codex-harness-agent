package com.myharness.agent.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myharness.agent.codex.CodexException;
import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.security.DeviceIdentityProvider;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Device-authenticated budget admission and durable, content-free usage outbox. */
@Component
public class ManagedUsageClient {
    private final AgentProperties properties;
    private final DeviceIdentityProvider identity;
    private final ObjectMapper json;
    private final Set<String> activeRequests=ConcurrentHashMap.newKeySet();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"managed-usage-outbox");t.setDaemon(true);return t;});
    public ManagedUsageClient(AgentProperties properties,DeviceIdentityProvider identity,ObjectMapper json) {
        this.properties=properties;this.identity=identity;this.json=json;
        recover();
        scheduler.scheduleWithFixedDelay(this::flush,10,15,TimeUnit.SECONDS);
    }
    public Request begin(String turnId,ObjectNode body,boolean compact) {
        if(turnId==null || !turnId.matches("[0-9]+"))throw new CodexException("缺少托管模型 Turn 标识");
        validateTools(body.get("tools"));
        JsonNode supplied=body.get("max_output_tokens");
        if(!compact && supplied!=null && !supplied.isNull() && (!supplied.canConvertToInt() || supplied.asInt()<1))
            throw new CodexException("无效的最大输出 token");
        String id=UUID.randomUUID().toString();
        ObjectNode request=json.createObjectNode().put("requestId",id).put("compact",compact);
        JsonNode permit=post(turnId,"reserve",request);
        int max=permit.path("maxOutputTokens").asInt(0);
        if(max<1 || !id.equals(permit.path("requestId").asText()))throw new CodexException("无效的模型预算授权");
        if(!compact) {
            body.put("max_output_tokens",supplied==null || supplied.isNull()?max:Math.min(max,supplied.asInt()));
        }
        ObjectNode payload=json.createObjectNode().put("turnId",turnId);
        payload.putObject("settlement").put("requestId",id).put("outcome","INTERRUPTED");
        activeRequests.add(id);
        try {write(id+".inflight",payload);}
        catch(RuntimeException failure){activeRequests.remove(id);throw failure;}
        return new Request(turnId,id,payload);
    }
    private void validateTools(JsonNode tools) {
        if(tools==null || tools.isNull())return;
        if(!tools.isArray())throw new CodexException("托管模型 tools 必须为工具数组");
        // Namespaces only group tools; inspect every leaf without flattening names or schemas.
        Deque<JsonNode> pending=new ArrayDeque<>();
        tools.forEach(pending::addLast);
        while(!pending.isEmpty()) {
            JsonNode tool=pending.removeFirst();
            String type=tool.path("type").asText("");
            if("namespace".equals(type)) {
                JsonNode children=tool.get("tools");
                if(children==null || !children.isArray())throw new CodexException("namespace 工具容器必须包含 tools 数组");
                children.forEach(pending::addLast);
            } else if(!Set.of("function","custom").contains(type)) {
                String label=type.matches("[a-zA-Z0-9_]{1,64}")?type:"unknown";
                throw new CodexException("当前计价规则不支持该工具类型（"+label+"），请检查托管模型工具配置");
            }
        }
    }
    public final class Request {
        private final String turnId,id;
        private final ObjectNode payload;
        private boolean finished;
        private Request(String turnId,String id,ObjectNode payload){this.turnId=turnId;this.id=id;this.payload=payload;}
        public synchronized void observe(JsonNode event) {
            String eventType=event.path("type").asText("");
            if(!eventType.isEmpty() && !Set.of("response.completed","response.incomplete","response.failed").contains(eventType))return;
            JsonNode response=event.has("response")?event.path("response"):event;
            if("in_progress".equals(response.path("status").asText()))return;
            JsonNode usage=response.path("usage");
            if(!usage.isObject())return;
            ObjectNode u=payload.withObject("settlement");
            // Required counts stay null when absent; no text-length estimates enter the ledger.
            copyNumber(usage,"input_tokens",u,"inputTokens");
            copyNumber(usage,"output_tokens",u,"outputTokens");
            copyNumber(usage.path("input_tokens_details"),"cached_tokens",u,"cachedTokens");
            JsonNode reasoning=usage.path("output_tokens_details").get("reasoning_tokens");
            if(reasoning==null)u.put("reasoningTokens",0);else copyNumber(usage.path("output_tokens_details"),"reasoning_tokens",u,"reasoningTokens");
            String rid=response.path("id").asText("");if(rid.length()<=200)u.put("responseId",rid);
            String model=response.path("model").asText("");if(model.length()<=128)u.put("actualModel",model);
            u.put("outcome","REPORTED");
            // Persist before forwarding the final SSE frame to Codex.
            write(id+".json",payload);
        }
        public synchronized void finish() {
            if(finished)return;
            boolean interrupted=Thread.interrupted();
            try {
                write(id+".json",payload);delete(id+".inflight");finished=true;
                if(!interrupted)try{post(turnId,"settle",payload.path("settlement"));delete(id+".json");}catch(RuntimeException ignored){/* durable retry */}
            } finally {activeRequests.remove(id);if(interrupted)Thread.currentThread().interrupt();}
        }
    }
    private void copyNumber(JsonNode source,String key,ObjectNode target,String field) {
        JsonNode n=source.get(key);if(n!=null && n.isIntegralNumber() && n.canConvertToLong() && n.asLong()>=0)target.put(field,n.asLong());else target.putNull(field);
    }
    private JsonNode post(String tid,String action,JsonNode body) {
        try {
            URI base=properties.getEnrollmentUrl();
            if(base==null && properties.getServerUrl()!=null) {
                URI server=properties.getServerUrl();
                if(!Set.of("ws","wss").contains(server.getScheme()) || server.getUserInfo()!=null)throw new CodexException("无效的服务端地址");
                base=new URI("wss".equals(server.getScheme())?"https":"http",null,server.getHost(),server.getPort(),"/",null,null);
            }
            if(base==null || base.getHost()==null || base.getUserInfo()!=null ||
                !("https".equals(base.getScheme()) || "http".equals(base.getScheme()) && Set.of("localhost","127.0.0.1","::1","[::1]").contains(base.getHost())))
                throw new CodexException("模型额度授权需要 HTTPS 或本机 HTTP 服务端");
            var device=identity.get();
            var req=HttpRequest.newBuilder(base.resolve("/api/v1/agent/turns/"+tid+"/usage/"+action))
                .timeout(Duration.ofSeconds(15)).header("Content-Type","application/json")
                .header("Authorization","Bearer "+device.getDeviceToken()).header("X-Harness-Device-Code",device.getDeviceCode())
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
            var response=http.send(req,HttpResponse.BodyHandlers.ofInputStream());
            try(var stream=response.body()) {
                byte[] bytes=stream.readNBytes(16385);if(bytes.length>16384)throw new CodexException("模型额度响应超限");
                JsonNode value=json.readTree(bytes);
                if(response.statusCode()!=200 || !"success".equals(value.path("status").asText())) {
                    String info=value.path("info").asText("模型额度授权失败");
                    throw new CodexException(info.length()>250?"模型额度授权失败":info);
                }
                return value.path("data");
            }
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new CodexException("模型额度请求已中断");}
        catch(CodexException e){throw e;}
        catch(Exception e){throw new CodexException("模型额度服务暂不可用，已停止新的模型请求");}
    }
    private Path directory(){return properties.getDataDir().resolve("managed-usage-outbox");}
    private synchronized void write(String name,ObjectNode value) {
        Path tmp=null;
        try {
            Files.createDirectories(directory());tmp=Files.createTempFile(directory(),"usage-",".tmp");
            try(FileChannel channel=FileChannel.open(tmp,StandardOpenOption.WRITE)) {
                ByteBuffer bytes=ByteBuffer.wrap(json.writeValueAsBytes(value));while(bytes.hasRemaining())channel.write(bytes);channel.force(true);
            }
            Files.move(tmp,directory().resolve(name),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } catch(Exception e){throw new CodexException("无法持久化模型用量，已停止请求");}
        finally {if(tmp!=null)try{Files.deleteIfExists(tmp);}catch(Exception ignored){}}
    }
    private synchronized void delete(String name){try{Files.deleteIfExists(directory().resolve(name));}catch(Exception ignored){}}
    private void recover() {
        if(properties.getDataDir()==null || !Files.isDirectory(directory()))return;
        try(var files=Files.list(directory())) {
            files.filter(p->p.getFileName().toString().matches("[a-f0-9-]{36}\\.inflight"))
                .filter(p->!activeRequests.contains(p.getFileName().toString().replace(".inflight",""))).forEach(p->{
                try {
                    Path target=p.resolveSibling(p.getFileName().toString().replace(".inflight",".json"));
                    if(Files.exists(target))Files.delete(p);else Files.move(p,target,StandardCopyOption.ATOMIC_MOVE);
                } catch(Exception ignored){}
            });
        }catch(Exception ignored){}
    }
    private synchronized void flush() {
        recover();
        if(properties.getDataDir()==null || !Files.isDirectory(directory()))return;
        try(var files=Files.list(directory())) {
            for(Path path:files.filter(p->p.getFileName().toString().matches("[a-f0-9-]{36}\\.json")).limit(10).toList()) {
                try {
                    if(Files.size(path)>8192)continue;
                    JsonNode value=json.readTree(Files.readAllBytes(path));String tid=value.path("turnId").asText();
                    if(!tid.matches("[0-9]+"))continue;
                    post(tid,"settle",value.path("settlement"));Files.deleteIfExists(path);
                }catch(Exception ignored){return;}
            }
        }catch(Exception ignored){}
    }
    @PreDestroy public void close(){scheduler.shutdownNow();http.shutdownNow();}
}
