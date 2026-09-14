package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.entity.dto.ModelRuntimeDTO;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Codex protocol with a deterministic local provider; never calls a remote model. */
@EnabledIfSystemProperty(named="codex.orchestration.smoke",matches="true")
class OrchestrationOutcomeSmokeTest {
    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void reportsWaitingThenCompletionOnTheSameResumedThread(boolean windowsToolDefinitions,@TempDir Path root,@TempDir Path data) throws Exception {
        var json=new ObjectMapper();var call=new AtomicBoolean(true);var state=new AtomicReference<>("WAITING_USER");
        var advertised=new LinkedBlockingQueue<String>();var provider=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        provider.createContext("/responses",exchange->{
            var request=json.readTree(exchange.getRequestBody().readAllBytes());boolean invoke=call.getAndSet(false);
            var item=json.createObjectNode().put("id",invoke?"fc-node":"msg-node").put("status","completed");
            if(invoke) {
                advertised.add(request.path("tools").toString());
                item.put("type","function_call").put("name",OrchestrationOutcomeTool.NAME).put("call_id","node-call")
                    .put("arguments",json.createObjectNode().put("state",state.get()).put("summary",state.get().equals("WAITING_USER")?"请指定店铺":"已生成报告").toString());
            } else item.put("type","message").put("role","assistant").putArray("content").addObject().put("type","output_text").put("text","节点回复").putArray("annotations");
            var added=json.createObjectNode().put("type","response.output_item.added").put("output_index",0);added.set("item",item);
            var done=json.createObjectNode().put("type","response.output_item.done").put("output_index",0);done.set("item",item);
            var complete=json.createObjectNode().put("type","response.completed");var response=complete.putObject("response").put("id","resp-node").put("status","completed");
            response.putArray("output").add(item);response.putObject("usage").put("input_tokens",1).put("output_tokens",1).put("total_tokens",2);
            var stream=new StringBuilder();for(var event:List.of(added,done,complete))stream.append("event: ").append(event.path("type").asText()).append("\ndata: ").append(event).append("\n\n");
            byte[] bytes=stream.toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,bytes.length);
            exchange.getResponseBody().write(bytes);exchange.close();
        });provider.start();
        try {
            var runtime=new ModelRuntimeDTO();runtime.setSchemaVersion(2);runtime.setRuntimeMode("MANAGED_PROVIDER");runtime.setRuntimeKey("d".repeat(64));runtime.setConfigurationVersionId(1L);
            runtime.setModelId("harness-node-probe");runtime.setProviderName("Local node fixture");runtime.setBaseUrl("http://127.0.0.1:"+provider.getAddress().getPort());runtime.setApiKey("synthetic-local-key");
            var properties=new AgentProperties();properties.setDataDir(data);properties.setCodexRequestTimeoutSeconds(20);
            var options=new CodexThreadOptions("node-probe",root,runtime).withExpertRuntime(List.of(),List.of()).withOrchestration(true);
            String thread=null;
            for(String outcome:List.of("WAITING_USER","COMPLETE")) {
                call.set(true);state.set(outcome);
                try(var adapter=new ConversationCodexGateway(()->new AppServerCodexAdapter(properties,json) {
                    @Override com.fasterxml.jackson.databind.JsonNode request(String method,com.fasterxml.jackson.databind.JsonNode params) {
                        if(windowsToolDefinitions && "thread/start".equals(method)) {
                            // Register the production Windows schemas only; do not initialize LPAC or execute commands.
                            var windows=json.createObjectNode();
                            WindowsExecutionTools.configure(windows,true,true,true);
                            WindowsExecutionTools.configureCommands(windows,properties);
                            var combined=windows.withArray("dynamicTools");
                            params.path("dynamicTools").forEach(combined::add);
                            ((com.fasterxml.jackson.databind.node.ObjectNode)params).set("dynamicTools",combined);
                        }
                        return super.request(method,params);
                    }
                })) {
                    if(thread==null)thread=adapter.startThread(options);else adapter.resumeThread(thread,options);
                    var finished=new CompletableFuture<String>();var report=new CompletableFuture<String>();
                    adapter.startTurn(thread,new CodexTurnInput("用户原始职责").withOrchestration(true),new CodexEventListener(){
                        public void onEvent(CodexEvent event) {}
                        public void onApproval(CodexApproval approval) {finished.completeExceptionally(new AssertionError("Unexpected approval"));}
                        public void onNodeOutcome(com.fasterxml.jackson.databind.JsonNode value){report.complete(value.path("state").asText());}
                        public void onCompleted(String id,String status,String reason){finished.complete(status);}
                    });
                    String tools=advertised.poll(30,TimeUnit.SECONDS);assertNotNull(tools);assertTrue(tools.contains(OrchestrationOutcomeTool.NAME));
                    if(windowsToolDefinitions)assertTrue(tools.contains(WindowsExecutionTools.NAME));
                    assertEquals(outcome,report.get(30,TimeUnit.SECONDS));assertEquals("completed",finished.get(30,TimeUnit.SECONDS));
                }
            }
        }finally{
            provider.stop(0);
        }
    }
}
