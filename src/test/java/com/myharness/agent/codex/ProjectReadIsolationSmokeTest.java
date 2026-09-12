package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.entity.dto.ModelRuntimeDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises real Codex shell execution. Only the model response is a local deterministic fixture. */
@EnabledIfSystemProperty(named="codex.isolation.smoke",matches="true")
class ProjectReadIsolationSmokeTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"harness_execute","harness_run_command","harness_view_image","harness_apply_patch","exec_command","shell_command","apply_patch","view_image"})
    void deniesExternalReadsOnNewAndResumedThreads(String requestedTool) throws Exception {
        String modeProbe=System.getProperty("windows.mode.probe");
        org.junit.jupiter.api.Assumptions.assumeTrue(modeProbe==null||requestedTool.equals("harness_execute"));
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows") || requestedTool.equals("harness_execute"));
        Path root=Files.createTempDirectory(Path.of("target").toAbsolutePath(),"read-isolation-").toRealPath();
        Path workspace=Files.createDirectory(root.resolve("project"));
        Path outside=Files.writeString(root.resolve("outside.txt"),"EXTERNAL_FIXTURE");
        Files.writeString(workspace.resolve("inside.txt"),"INTERNAL_FIXTURE");
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2,3,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",workspace.resolve("fixture.png").toFile());
        String command="""
                $ErrorActionPreference='Stop'
                if ((Get-Content -LiteralPath './inside.txt' -Raw) -ne 'INTERNAL_FIXTURE') { throw 'inside read failed' }
                Set-Content -LiteralPath './created.txt' -Value 'WRITE_OK'
                $readDenied=$false
                try { $null=Get-Content -LiteralPath '%s' -Raw } catch [System.UnauthorizedAccessException] { $readDenied=$true }
                $writeDenied=$false
                try { Set-Content -LiteralPath '%s' -Value 'EXTERNAL_WRITE' } catch [System.UnauthorizedAccessException] { $writeDenied=$true }
                Write-Output ('ISOLATION_RESULT readDenied=' + $readDenied + ' writeDenied=' + $writeDenied)
                """.formatted(outside.toString().replace("'","''"),outside.toString().replace("'","''"));
        boolean nativeWindows=System.getProperty("os.name").startsWith("Windows");
        if(nativeWindows) command="""
                from pathlib import Path
                assert Path('inside.txt').read_text()=='INTERNAL_FIXTURE'
                Path('created.txt').write_text('WRITE_OK')
                outside=Path(%s)
                readDenied=writeDenied=False
                try: outside.read_bytes()
                except PermissionError: readDenied=True
                try: outside.write_text('EXTERNAL_WRITE')
                except PermissionError: writeDenied=True
                print(f'ISOLATION_RESULT readDenied={readDenied} writeDenied={writeDenied}')
                """.formatted(new ObjectMapper().writeValueAsString(outside.toString()));
        if("Linux".equalsIgnoreCase(System.getProperty("os.name"))) command="""
                set -eu
                test "$(cat ./inside.txt)" = INTERNAL_FIXTURE
                printf WRITE_OK > ./created.txt
                readDenied=True; writeDenied=True
                if cat '../outside.txt' >/dev/null 2>&1; then readDenied=False; fi
                if cat '%s' >/dev/null 2>&1; then readDenied=False; fi
                ln -sf '%s' ./outside-link
                if cat ./outside-link >/dev/null 2>&1; then readDenied=False; fi
                if (printf EXTERNAL_WRITE > '../outside.txt') 2>/dev/null; then writeDenied=False; fi
                printf 'ISOLATION_RESULT readDenied=%%s writeDenied=%%s\\n' "$readDenied" "$writeDenied"
                """.formatted(outside.toString().replace("'","'\"'\"'"),outside.toString().replace("'","'\"'\"'"));
        final String shellCommand=command;
        var json=new ObjectMapper();var callTool=new AtomicBoolean(true);var continuation=new AtomicReference<String>();
        var childContinuation=new AtomicReference<CompletableFuture<String>>(new CompletableFuture<>());
        var forkContext=new AtomicBoolean();
        var childCallTool=new AtomicBoolean(true);
        var childDenial=new AtomicReference<String>();
        var provider=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var providerWorkers=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();provider.setExecutor(providerWorkers);
        provider.createContext("/responses",exchange -> {
            String input=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            boolean child=exchange.getRequestHeaders().getFirst("x-openai-subagent")!=null;
            if(child)Files.writeString(Path.of("target/mode-probe-child-tools.json"),json.readTree(input).path("tools").toPrettyString());
            if(modeProbe!=null&&!child)Files.writeString(Path.of("target/mode-probe-"+modeProbe+".json"),json.readTree(input).path("tools").toPrettyString());
            boolean tool=child?childCallTool.getAndSet(false):callTool.getAndSet(false);
            ObjectNode item=json.createObjectNode().put("id",tool?"fc-isolation":"msg-isolation").put("status","completed");
            if(tool) {
                item.put("type","function_call").put("name",nativeWindows?requestedTool:"exec_command").put("call_id","call-isolation");
                item.put("arguments",nativeWindows?json.createObjectNode().put("script",shellCommand).toString():json.createObjectNode().put("cmd",shellCommand).put("max_output_tokens",1000).put("yield_time_ms",10000).toString());
                if(nativeWindows&&requestedTool.equals("harness_view_image"))item.put("arguments",json.createObjectNode().put("path","fixture.png").toString());
                if(nativeWindows&&requestedTool.equals("harness_run_command")) {
                    var args=json.createObjectNode().put("program","python");args.putArray("args").add("-c").add(shellCommand);item.put("arguments",args.toString());
                }
                if(nativeWindows&&requestedTool.equals("harness_apply_patch"))item.put("arguments",json.createObjectNode().put("patch","*** Begin Patch\n*** Update File: patch.txt\n@@\n-old\n+new\n*** End Patch").toString());
                if("code_mode".equals(modeProbe)) {
                    String arguments=item.path("arguments").asText();item.remove("arguments");
                    item.put("type","custom_tool_call").put("name","exec").put("input","text(ALL_TOOLS.map(t=>t.name)); text(await tools.harness_execute("+arguments+"));");
                }
                if("multi_agent".equals(modeProbe)&&!child) {
                    item.put("name","spawn_agent").put("namespace","multi_agent_v1").put("arguments",json.createObjectNode().put("message","Run the synthetic child isolation fixture.").put("fork_context",forkContext.get()).toString());
                }
            } else {
                if(child)childContinuation.get().complete(input);else continuation.set(input);
                item.put("type","message").put("role","assistant");
                item.putArray("content").addObject().put("type","output_text").put("text","ISOLATION_FINISHED").putArray("annotations");
            }
            var added=json.createObjectNode().put("type","response.output_item.added").put("output_index",0);added.set("item",item);
            var done=json.createObjectNode().put("type","response.output_item.done").put("output_index",0);done.set("item",item);
            var completed=json.createObjectNode().put("type","response.completed");
            var response=completed.putObject("response").put("id","resp-isolation").put("status","completed");
            response.putArray("output").add(item);response.putObject("usage").put("input_tokens",1).put("output_tokens",1).put("total_tokens",2);
            var stream=new StringBuilder();for(var event:List.of(added,done,completed))
                stream.append("event: ").append(event.path("type").asText()).append("\ndata: ").append(event).append("\n\n");
            byte[] bytes=stream.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,bytes.length);
            exchange.getResponseBody().write(bytes);exchange.close();
        });provider.start();
        try {
            var runtime=new ModelRuntimeDTO();runtime.setSchemaVersion(2);runtime.setRuntimeMode("MANAGED_PROVIDER");
            runtime.setRuntimeKey("d".repeat(64));runtime.setConfigurationVersionId(1L);runtime.setModelId("harness-isolation-probe");
            runtime.setInputModalities(List.of("TEXT","IMAGE"));
            runtime.setProviderName("Local isolation fixture");runtime.setBaseUrl("http://127.0.0.1:"+provider.getAddress().getPort());runtime.setApiKey("synthetic-local-test-key");
            var properties=new AgentProperties();properties.setDataDir(root.resolve("data"));properties.setCodexRequestTimeoutSeconds(20);
            Files.createDirectories(properties.getDataDir());
            if(nativeWindows) properties.setWindowsPython(Path.of(System.getProperty("windows.isolation.python")));
            var options=new CodexThreadOptions("isolation-probe",workspace,runtime).withExpertRuntime(List.of(),List.of());
            String thread=null;
            for(int iteration=0;iteration<2;iteration++) {
                callTool.set(true);continuation.set(null);
                childContinuation.set(new CompletableFuture<>());
                childCallTool.set(true);
                childDenial.set(null);
                forkContext.set(iteration>0);
                Files.writeString(workspace.resolve("patch.txt"),"old\n");
                try(var adapter=new AppServerCodexAdapter(properties,json) {
                    @Override void write(com.fasterxml.jackson.databind.JsonNode message) {
                        if(message.has("error"))childDenial.set(message.path("error").path("message").asText());
                        super.write(message);
                    }
                    @Override com.fasterxml.jackson.databind.JsonNode request(String method,com.fasterxml.jackson.databind.JsonNode params) {
                        if(modeProbe!=null&&(method.equals("thread/start")||method.equals("thread/resume"))) {
                            var features=((ObjectNode)params).withObject("config").withObject("features");
                            if(modeProbe.equals("code_mode"))features.put("code_mode",true).put("code_mode_host",true);
                            else if(modeProbe.equals("multi_agent"))features.put("multi_agent",true);
                            else throw new IllegalArgumentException("Unknown mode probe");
                        }
                        return super.request(method,params);
                    }
                }) {
                    if(thread==null) thread=adapter.startThread(options);else adapter.resumeThread(thread,options);
                    var finished=new CompletableFuture<String>();
                    adapter.startTurn(thread,new CodexTurnInput("Run the synthetic isolation fixture."),new CodexEventListener() {
                        public void onEvent(CodexEvent event) { }
                        public void onApproval(CodexApproval approval) { finished.completeExceptionally(new AssertionError("Unexpected escalation")); }
                        public void onCompleted(String id,String status,String reason) { finished.complete(status); }
                    });
                    assertEquals("completed",finished.get(45,TimeUnit.SECONDS));
                    assertNotNull(continuation.get());
                    if(nativeWindows&&modeProbe==null) for(var tool:json.readTree(continuation.get()).path("tools")) {
                        String name=tool.path("name").asText(tool.path("type").asText());
                        assertTrue(List.of("harness_execute","harness_run_command","harness_apply_patch","harness_view_image","request_user_input","get_goal","create_goal","update_goal","web_search").contains(name),"Unexpected tool outside Windows isolation: "+name);
                    }
                    String output="";
                    for(var item:json.readTree(continuation.get()).path("input"))
                        if(List.of("function_call_output","custom_tool_call_output").contains(item.path("type").asText())) output+=item.path("output").toString();
                    if(modeProbe!=null)Files.writeString(Path.of("target/mode-probe-"+modeProbe+"-result.txt"),output);
                    if("multi_agent".equals(modeProbe)) {
                        String childInput=childContinuation.get().get(25,TimeUnit.SECONDS);
                        var results=json.createArrayNode();for(var childItem:json.readTree(childInput).path("input"))
                            if("function_call_output".equals(childItem.path("type").asText()))results.add(childItem.path("output"));
                        ObjectNode evidence=json.createObjectNode().put("adapterDenial",childDenial.get());evidence.set("results",results);
                        Files.writeString(Path.of("target/mode-probe-child-"+iteration+"-result.json"),evidence.toPrettyString());
                        if(iteration==0)assertTrue(results.toString().contains("unsupported call: harness_execute"),results.toString());
                        else {assertTrue(results.toString().contains("dynamic tool request failed"),results.toString());assertEquals("Unknown isolated command or inactive turn",childDenial.get());}
                        assertFalse(Files.exists(workspace.resolve("created.txt")));continue;
                    }
                    assertEquals("EXTERNAL_FIXTURE",Files.readString(outside));
                    if(nativeWindows&&requestedTool.equals("harness_view_image")) {
                        assertTrue(continuation.get().contains("data:image/png;base64,"),"Model did not receive image pixels");
                        assertTrue(continuation.get().contains("input_image"),"Model did not receive image content");continue;
                    }
                    if(nativeWindows&&requestedTool.equals("harness_apply_patch")) {
                        assertEquals("new\n",Files.readString(workspace.resolve("patch.txt")));assertTrue(output.contains("Update patch.txt"),output);continue;
                    }
                    if(nativeWindows && !List.of("harness_execute","harness_run_command").contains(requestedTool)) {
                        assertTrue(output.contains("unsupported call"),output);
                        assertFalse(Files.exists(workspace.resolve("created.txt")));
                        continue;
                    }
                    assertTrue(output.contains("ISOLATION_RESULT readDenied=True writeDenied=True"),"Actual shell result: "+output);
                    assertEquals("EXTERNAL_FIXTURE",Files.readString(outside));
                    assertTrue(Files.readString(workspace.resolve("created.txt")).contains("WRITE_OK"));
                }
            }
        } finally {
            provider.stop(0);
            providerWorkers.shutdownNow();
            if(nativeWindows) WindowsIsolatedCommand.cleanupProfile(workspace,Path.of(System.getProperty("windows.isolation.python")));
            assertTrue(root.startsWith(Path.of("target").toRealPath()));
            try(var paths=Files.walk(root)) {for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);}
        }
    }
}
