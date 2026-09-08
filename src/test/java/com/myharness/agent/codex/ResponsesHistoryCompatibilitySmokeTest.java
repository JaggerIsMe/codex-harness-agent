package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.entity.dto.ModelRuntimeDTO;
import com.myharness.agent.entity.enums.TurnEventType;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** Real installed Codex, deterministic loopback Responses provider, no external inference. */
@EnabledIfSystemProperty(named="codex.smoke",matches="true")
class ResponsesHistoryCompatibilitySmokeTest {
    private final ObjectMapper json=new ObjectMapper();
    private final boolean searchProbe=Boolean.getBoolean("codex.search.smoke");

    @ParameterizedTest @ValueSource(booleans={false,true})
    void switchesReasoningHistoryWithoutChangingThreadOrSource(boolean withExpertSkill,@TempDir Path workspace,@TempDir Path data) throws Exception {
        List<CodexSkillInput> skills=List.of();
        if(withExpertSkill) {
            Path skill=Files.createDirectories(workspace.resolve(".harness/expert-runtimes/1/probe")).resolve("SKILL.md");
            Files.writeString(skill,"---\nname: hello-skill\ndescription: Isolated provider switching regression fixture.\n---\nReply HISTORY_OK.\n");
            skills=List.of(new CodexSkillInput("hello-skill",skill.toRealPath().toString()));
        }
        var strict=new AtomicBoolean(false);
        var requests=new CopyOnWriteArrayList<JsonNode>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/responses",exchange->{
            JsonNode body=json.readTree(exchange.getRequestBody()); requests.add(body);
            boolean foreign=false;
            for(JsonNode item:body.path("input")) if("reasoning".equals(item.path("type").asText())) foreign=true;
            if(searchProbe) for(JsonNode item:body.path("input")) {
                if("web_search_call".equals(item.path("type").asText())
                        && !item.path("action").toString().contains(strict.get()?"documentation B":"documentation A")) foreign=true;
            }
            if(strict.get() && foreign) {
                byte[] error="{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"input reasoning.content: maximum length 0, got 1\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(400,error.length);exchange.getResponseBody().write(error);exchange.close();return;
            }
            var message=json.createObjectNode().put("type","message").put("id","msg-"+requests.size()).put("role","assistant").put("status","completed");
            message.putArray("content").addObject().put("type","output_text").put("text","HISTORY_OK").putArray("annotations");
            var items=json.createArrayNode();
            if(searchProbe) {
                var search=items.addObject().put("type","web_search_call").put("id",(strict.get()?"search-b-":"search-a-")+requests.size()).put("status","completed");
                search.putObject("action").put("type","search").putArray("queries").add("OpenAI Responses web search documentation "+(strict.get()?"B":"A")+requests.size());
            }
            if(!strict.get()) {
                var reasoning=items.addObject().put("type","reasoning").put("id","reasoning-probe").put("encrypted_content","provider-specific-opaque-state");
                reasoning.putArray("summary");reasoning.putArray("content").addObject().put("type","reasoning_text").put("text","Synthetic provider reasoning");
            }
            items.add(message);
            StringBuilder sse=new StringBuilder();int index=0;
            for(JsonNode item:items) {
                for(String kind:List.of("response.output_item.added","response.output_item.done")) {
                    var event=json.createObjectNode().put("type",kind).put("output_index",index);event.set("item",item);
                    sse.append("event: ").append(kind).append("\ndata: ").append(event).append("\n\n");
                } index++;
            }
            var end=json.createObjectNode().put("type","response.completed");
            var response=end.putObject("response").put("id","resp-"+requests.size()).put("status","completed");response.set("output",items);
            response.putObject("usage").put("input_tokens",10).put("output_tokens",10).put("total_tokens",20);
            sse.append("event: response.completed\ndata: ").append(end).append("\n\n");
            byte[] bytes=sse.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,bytes.length);
            exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            var properties=new AgentProperties();properties.setDataDir(data);properties.setCodexRequestTimeoutSeconds(30);
            properties.setResponsesHistoryCompatibility(true);
            ModelRuntimeDTO a=runtime(server,"a"),b=runtime(server,"b");
            String id;Path original;
            try(var adapter=new AppServerCodexAdapter(properties,json)) {
                id=adapter.startThread(options(workspace,a,skills));reply(adapter,id,skills);
                if(searchProbe) reply(adapter,id,skills);
                original=Path.of(rpc(adapter,"thread/read",json.createObjectNode().put("threadId",id).put("includeTurns",false)).path("thread").path("path").asText());
            }
            byte[] originalBytes=Files.readAllBytes(original);
            assertTrue(new String(originalBytes,StandardCharsets.UTF_8).contains("Synthetic provider reasoning"));
            strict.set(true);
            for(ModelRuntimeDTO target:List.of(b,b,a)) {
                if(target==a && Boolean.getBoolean("codex.turn.smoke")) {
                    var local=new ModelRuntimeDTO();local.setSchemaVersion(2);local.setRuntimeMode("LOCAL_CODEX");local.setRuntimeKey("c".repeat(64));
                    try(var adapter=new AppServerCodexAdapter(properties,json)) {
                        adapter.resumeThread(id,options(workspace,local,skills));
                        var bridge=(ResponsesCompatibilityProxy)ReflectionTestUtils.getField(adapter,"historyProxy");
                        assertNotNull(bridge);
                        try {reply(adapter,id,skills);if(searchProbe) reply(adapter,id,skills);} catch(Exception | AssertionError error) {throw new AssertionError(bridge.diagnostics(),error);}
                        assertTrue(bridge.requestCount()>0,"Local inference must cross the compatibility bridge: "+bridge.diagnostics());
                    }
                }
                strict.set(target!=a);
                try(var adapter=new AppServerCodexAdapter(properties,json)) {
                    adapter.resumeThread(id,options(workspace,target,skills));reply(adapter,id,skills);
                    if(searchProbe) reply(adapter,id,skills);
                }
            }
            if(searchProbe && Boolean.getBoolean("codex.turn.smoke")) {
                // Local searched above, then A searched. Return to Local and require another real search.
                var local=new ModelRuntimeDTO();local.setSchemaVersion(2);local.setRuntimeMode("LOCAL_CODEX");local.setRuntimeKey("c".repeat(64));
                try(var adapter=new AppServerCodexAdapter(properties,json)) {
                    adapter.resumeThread(id,options(workspace,local,skills));
                    reply(adapter,id,skills);
                    var bridge=(ResponsesCompatibilityProxy)ReflectionTestUtils.getField(adapter,"historyProxy");
                    assertNotNull(bridge);assertTrue(bridge.requestCount()>0);
                }
            }
            assertArrayEquals(originalBytes,Arrays.copyOf(Files.readAllBytes(original),originalBytes.length),"Existing history must remain untouched; only native appends are allowed");
            assertEquals(searchProbe?8:4,requests.size());
            assertTrue(requests.getLast().path("input").toString().contains("HISTORY_OK"));
            assertTrue(requests.getLast().path("input").toString().contains("provider-specific-opaque-state"),"Switching back must preserve known native state");
            if(searchProbe) {
                assertTrue(requests.get(2).path("input").toString().contains("historical_web_search"));
                assertTrue(requests.getLast().path("input").toString().contains("documentation A1"),"Switching back keeps original A search history");
                assertTrue(requests.getLast().path("input").toString().contains("documentation A7"),"A can search again after switching back");
                assertTrue(requests.getLast().path("input").toString().contains("documentation B3"),"B search evidence is retained");
                boolean originalNative=false,newNative=false;
                for(JsonNode item:requests.getLast().path("input")) if("web_search_call".equals(item.path("type").asText())) {
                    originalNative|=item.path("action").toString().contains("documentation A1");
                    newNative|=item.path("action").toString().contains("documentation A7");
                }
                assertTrue(originalNative && newNative,"Old and newly executed A searches must remain native after switching back");
            }
        } finally {server.stop(0);}
    }
    private ModelRuntimeDTO runtime(HttpServer server,String key) {
        var value=new ModelRuntimeDTO();value.setSchemaVersion(2);value.setRuntimeMode("MANAGED_PROVIDER");
        value.setRuntimeKey(key.repeat(64));value.setConfigurationVersionId(1L);value.setModelId("history-probe");
        value.setProviderName("History probe");value.setApiKey("test-only-key");value.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());return value;
    }
    private JsonNode rpc(AppServerCodexAdapter adapter,String method,ObjectNode params) {
        return ReflectionTestUtils.invokeMethod(adapter,"request",method,params);
    }
    private CodexThreadOptions options(Path workspace,ModelRuntimeDTO runtime,List<CodexSkillInput> skills) {
        var options=new CodexThreadOptions("history-probe",workspace,runtime);
        return skills.isEmpty()?options:options.withExpertSkills(skills);
    }
    private void reply(AppServerCodexAdapter adapter,String id,List<CodexSkillInput> skills) throws Exception {
        var finished=new CompletableFuture<String>();
        var answer=new StringBuffer();
        var warning=new java.util.concurrent.atomic.AtomicReference<String>("");
        var transportWarnings=new CopyOnWriteArrayList<String>();
        var eventTypes=java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
        // Thread metadata can retain its original provider, so use the active model remembered by the adapter.
        var models=(Map<?,?>)ReflectionTestUtils.getField(adapter,"threadModels");
        boolean local=models!=null && !"history-probe".equals(models.get(id));
        Path journal=Path.of(rpc(adapter,"thread/read",json.createObjectNode().put("threadId",id).put("includeTurns",false)).path("thread").path("path").asText());
        int originalLines=Files.exists(journal)?Files.readAllLines(journal).size():0;
        var input=new CodexTurnInput(searchProbe && local
                ? "Use exec to call tools.web__run with search_query for OpenAI Responses web search documentation. Do not use shell or other search tools. Then reply HISTORY_OK and an actual returned source URL. If the tool fails, report TOOL_FAILED instead."
                : "Reply HISTORY_OK. No tools.");
        if(!skills.isEmpty()) input.withExpert("Follow the user's request. Skills are optional for this test.",skills);
        adapter.startTurn(id,input,new CodexEventListener() {
            public void onEvent(CodexEvent event) {
                if(event.getType()==TurnEventType.WARNING) warning.set(CodexDiagnostics.redact(event.getContent()+" "+event.getDetails().path("error").path("additionalDetails").asText("")));
                if(event.getType()==TurnEventType.WARNING && String.valueOf(event.getContent()).matches("(?s).*(Reconnecting|Falling back from WebSockets).*$")) transportWarnings.add("transport retry");
                if(event.getDetails()!=null) eventTypes.add(event.getDetails().path("type").asText());
                if(event.getType()==TurnEventType.AGENT_MESSAGE_DELTA) answer.append(event.getContent());
                if(event.getType()==TurnEventType.ITEM_COMPLETED && "agentMessage".equals(event.getDetails().path("type").asText())) answer.append(event.getDetails().path("text").asText());
            }
            public void onApproval(CodexApproval approval) {finished.completeExceptionally(new AssertionError("Unexpected approval"));}
            public void onCompleted(String turnId,String status,String reason) {
                if("completed".equals(status)) finished.complete(status);
                else finished.completeExceptionally(new AssertionError(CodexDiagnostics.redact(reason)));
            }
        });
        try {assertEquals("completed",finished.get(Boolean.getBoolean("codex.turn.smoke")?90:30,TimeUnit.SECONDS));}
        catch(TimeoutException timeout) {throw new AssertionError("Turn timed out; last warning: "+warning.get(),timeout);}
        assertTrue(answer.toString().contains("HISTORY_OK"),"Turn must produce an assistant answer");
        if(searchProbe && local) {
            assertTrue(eventTypes.contains("webSearch"),"Local model must actually search, events="+eventTypes);
            assertTrue(transportWarnings.isEmpty(),"HTTP-only bridge must not cause reconnect warnings");
            var rows=Files.readAllLines(journal).stream().skip(originalLines).map(line->{try{return json.readTree(line);}catch(Exception e){throw new RuntimeException(e);}}).toList();
            ToolExecutionAssertions.webResults(rows,false);
        }
    }
}
