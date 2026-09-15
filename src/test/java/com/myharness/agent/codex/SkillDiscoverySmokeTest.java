package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.entity.dto.ModelRuntimeDTO;
import com.myharness.agent.workspace.AgentStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real App Server and LPAC, deterministic local model; never calls an external model. */
@EnabledIfSystemProperty(named="skill.discovery.smoke",matches="true")
class SkillDiscoverySmokeTest {
    @TempDir Path root;

    @Test void newAndResumedModelRequestsDiscoverMetadataAndReadBodyThroughTheTool() throws Exception {
        var json=new ObjectMapper();
        Path workspace=Files.createDirectory(root.resolve("project")),data=Files.createDirectory(root.resolve("data"));
        AgentStorage.protectDataDirectory(data);
        String key="a".repeat(64);
        Path pkg=AgentStorage.directory(AgentStorage.workspaceRoot(data,workspace),"expert-runtimes/38/"+key+"/skills/pkg");
        Path skill=Files.writeString(pkg.resolve("SKILL.md"),"---\nname: fixture-skill\ndescription: SKILL_INDEX_FIXTURE\n---\nSKILL_BODY_FIXTURE\n");
        Files.writeString(pkg.resolve("unused.py"),"UNUSED_SOURCE_FIXTURE");
        boolean publicApi=Boolean.getBoolean("windows.public-api.skill");
        Path apiScript=pkg.resolve("check_api.py");
        if(publicApi) Files.copy(Path.of("../../examples/public-api-skill/harness-public-api-check/scripts/check_api.py"),apiScript);
        var apiOutput=new AtomicReference<Path>();
        var requests=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var first=new AtomicBoolean(true);
        var provider=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var workers=Executors.newVirtualThreadPerTaskExecutor();provider.setExecutor(workers);
        provider.createContext("/responses",exchange->{
            requests.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            boolean call=first.getAndSet(false);
            var item=json.createObjectNode().put("id",call?"fc-skill":"msg-skill").put("status","completed");
            if(call) {
                String command="from pathlib import Path;print(Path("+json.writeValueAsString(skill.toString())+").read_text())";
                if(publicApi) command+="\nimport subprocess,sys\nresult=subprocess.run([sys.executable,'-I','-S',"+json.writeValueAsString(apiScript.toString())+",'--output',"+json.writeValueAsString(apiOutput.get().toString())+"])\nassert result.returncode==0";
                var args=json.createObjectNode().put("script",command).put("timeout_seconds",90);
                item.put("type","function_call").put("name","harness_execute").put("call_id","call-skill").put("arguments",args.toString());
            } else {
                item.put("type","message").put("role","assistant");
                item.putArray("content").addObject().put("type","output_text").put("text","Completed.").putArray("annotations");
            }
            var added=json.createObjectNode().put("type","response.output_item.added").put("output_index",0);added.set("item",item);
            var done=json.createObjectNode().put("type","response.output_item.done").put("output_index",0);done.set("item",item);
            var completed=json.createObjectNode().put("type","response.completed");
            var response=completed.putObject("response").put("id","resp-skill").put("status","completed");
            response.putArray("output").add(item);response.putObject("usage").put("input_tokens",1).put("output_tokens",1).put("total_tokens",2);
            var stream=new StringBuilder();for(var event:List.of(added,done,completed))
                stream.append("event: ").append(event.path("type").asText()).append("\ndata: ").append(event).append("\n\n");
            byte[] bytes=stream.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,bytes.length);
            exchange.getResponseBody().write(bytes);exchange.close();
        });provider.start();
        try {
            var runtime=new ModelRuntimeDTO();runtime.setSchemaVersion(2);runtime.setRuntimeMode("MANAGED_PROVIDER");
            runtime.setRuntimeKey("d".repeat(64));runtime.setConfigurationVersionId(1L);runtime.setModelId("harness-skill-fixture");
            runtime.setBaseUrl("http://127.0.0.1:"+provider.getAddress().getPort());runtime.setApiKey("synthetic-test-token");
            var properties=new AgentProperties();properties.setDataDir(data);properties.setCodexRequestTimeoutSeconds(20);
            properties.setWindowsPython(Path.of(System.getProperty("windows.isolation.python")));
            if(publicApi)properties.setCommandNetworkMode(AgentProperties.CommandNetworkMode.PUBLIC);
            var skills=List.of(new CodexSkillInput("fixture-skill",skill.toString()));
            var options=new CodexThreadOptions("6",workspace,runtime).withExpertRuntime(skills,List.of()).withExecutionIdentity("38",key);
            String thread=null;
            for(int iteration=0;iteration<2;iteration++) {
                first.set(true);requests.clear();
                apiOutput.set(workspace.resolve("api-"+iteration+".json"));
                try(var adapter=new AppServerCodexAdapter(properties,json)) {
                    if(thread==null)thread=adapter.startThread(options);else adapter.resumeThread(thread,options);
                    var finished=new CompletableFuture<String>();
                    adapter.startTurn(thread,new CodexTurnInput("Use fixture-skill").withExpert("Use appropriate Skills.",skills),new CodexEventListener() {
                        public void onEvent(CodexEvent event) { }
                        public void onApproval(CodexApproval approval) {finished.completeExceptionally(new AssertionError("Unexpected approval"));}
                        public void onCompleted(String id,String status,String reason) {finished.complete(status+":"+reason);}
                    });
                    assertTrue(finished.get(publicApi?120:50,TimeUnit.SECONDS).startsWith("completed:"));
                    assertTrue(requests.size()>=2);
                    assertTrue(requests.getFirst().contains("SKILL_INDEX_FIXTURE"));
                    if(iteration==0)assertEquals(1,requests.getFirst().split("SKILL_INDEX_FIXTURE",-1).length-1);
                    if(iteration==0)assertFalse(requests.getFirst().contains("SKILL_BODY_FIXTURE"));
                    assertTrue(requests.getLast().contains("SKILL_BODY_FIXTURE"));
                    assertTrue(requests.stream().noneMatch(request->request.contains("UNUSED_SOURCE_FIXTURE")));
                    if(publicApi) {
                        var report=json.readTree(Files.readString(apiOutput.get()));
                        assertEquals("PUBLIC_API_SCRIPT_OK",report.path("marker").asText());
                        assertEquals(apiScript.toString(),report.path("entrypoint").asText());
                        assertTrue(requests.getFirst().contains("Network mode: PUBLIC"));
                        assertTrue(requests.getLast().contains("PUBLIC_API_SCRIPT_OK"));
                    }
                }
            }
        } finally {provider.stop(0);workers.shutdownNow();}
    }
}
