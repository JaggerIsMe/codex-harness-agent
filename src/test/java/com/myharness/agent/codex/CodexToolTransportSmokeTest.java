package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.entity.dto.ModelRuntimeDTO;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Uses the installed, authenticated Codex in an isolated workspace; never calls production MCP tools. */
@EnabledIfSystemProperty(named="codex.tools.smoke", matches="true")
class CodexToolTransportSmokeTest {
    @Test void standaloneWebReturnsResults(@TempDir Path workspace, @TempDir Path data) throws Exception {
        webResults(workspace,data,false);
    }
    @Test void standaloneWeatherReturnsForecast(@TempDir Path workspace, @TempDir Path data) throws Exception {
        webResults(workspace,data,true);
    }
    @Test void standaloneWeatherWithoutBridgeReturnsForecast(@TempDir Path workspace,@TempDir Path data) throws Exception {
        webResults(workspace,data,true,false);
    }
    private void webResults(Path workspace,Path data,boolean weather) throws Exception {
        webResults(workspace,data,weather,true);
    }
    private void webResults(Path workspace,Path data,boolean weather,boolean compatibility) throws Exception {
        var json=new ObjectMapper();
        var properties=new AgentProperties();properties.setDataDir(data);properties.setResponsesHistoryCompatibility(compatibility);
        var local=new ModelRuntimeDTO();local.setSchemaVersion(2);local.setRuntimeMode("LOCAL_CODEX");local.setRuntimeKey("d".repeat(64));
        try(var adapter=new AppServerCodexAdapter(properties,json)) {
            String id=adapter.startThread(new CodexThreadOptions("tools-probe",workspace,local).withExpertRuntime(List.of(),List.of()));
            var bridge=(ResponsesCompatibilityProxy)ReflectionTestUtils.getField(adapter,"historyProxy");
            var routes=new CopyOnWriteArrayList<String>();
            if(compatibility) {
                assertNotNull(bridge);
                var server=(HttpServer)ReflectionTestUtils.getField(bridge,"server");
                String prefix=URI.create(bridge.baseUrl()).getPath();
                server.removeContext("/");
                server.createContext("/",exchange->{
                    String suffix=exchange.getRequestURI().getRawPath().replace(prefix,"");
                    ReflectionTestUtils.invokeMethod(bridge,"handle",exchange);
                    routes.add(exchange.getRequestMethod()+" "+suffix.replaceAll("[^a-zA-Z0-9/_-]","?")+" -> "+exchange.getResponseCode());
                });
            } else assertNull(bridge,"Control must bypass the bridge entirely");
            var finished=new CompletableFuture<String>();
            adapter.startTurn(id,new CodexTurnInput("Use the web tool through exec: text(await tools.web__run({search_query:[{q:'OpenAI Responses web search documentation'}],response_length:'short'})); "
                    +(weather?"Then call text(await tools.web__run({weather:[{location:'Shenzhen, China',duration:1}],response_length:'short'})); ":"")
                    +"Do not use shell, MCP, or alternative search tools. Report actual tool results. If a tool fails, report TOOL_FAILED; otherwise report TOOLS_OK and one returned source URL."),new CodexEventListener() {
                public void onEvent(CodexEvent event) { }
                public void onApproval(CodexApproval approval) {finished.completeExceptionally(new AssertionError("Unexpected approval"));}
                public void onCompleted(String turnId,String status,String reason) {finished.complete(status);}
            });
            assertEquals("completed",finished.get(120,TimeUnit.SECONDS));
            var result=(com.fasterxml.jackson.databind.JsonNode)ReflectionTestUtils.invokeMethod(adapter,"request","thread/read",json.createObjectNode().put("threadId",id).put("includeTurns",false));
            String journal=Files.readString(Path.of(result.path("thread").path("path").asText()));
            System.out.println("Tool transport routes: "+routes);
            assertFalse(journal.contains("Unsupported compatibility bridge route"),"Standalone tool rejected; routes="+routes);
            if(compatibility) assertTrue(routes.contains("POST /alpha/search -> 200"),"Standalone endpoint must return success");
            var rows=Files.readAllLines(Path.of(result.path("thread").path("path").asText())).stream().map(line->{try{return json.readTree(line);}catch(Exception e){throw new RuntimeException(e);}}).toList();
            ToolExecutionAssertions.webResults(rows,weather);
        }
    }

    @Test void shellPatchAndMcpExecuteWithCompatibilityEnabled(@TempDir Path workspace,@TempDir Path data) throws Exception {
        var json=new ObjectMapper();String fileToken=UUID.randomUUID().toString(),mcpToken=UUID.randomUUID().toString();
        Files.writeString(workspace.resolve("probe.txt"),fileToken+"\n");
        var called=new java.util.concurrent.atomic.AtomicInteger();
        var header=new java.util.concurrent.atomic.AtomicReference<String>();
        var server=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/mcp",exchange->{
            if(!"POST".equals(exchange.getRequestMethod())) {exchange.sendResponseHeaders(405,-1);exchange.close();return;}
            var req=json.readTree(exchange.getRequestBody());
            if(!req.has("id")) {exchange.sendResponseHeaders(202,-1);exchange.close();return;}
            var response=json.createObjectNode().put("jsonrpc","2.0");response.set("id",req.get("id"));
            var result=response.putObject("result");
            switch(req.path("method").asText()) {
                case "initialize" -> {result.put("protocolVersion","2025-06-18");result.putObject("capabilities").putObject("tools");result.putObject("serverInfo").put("name","harness-tool-probe").put("version","1");}
                case "tools/list" -> {var tool=result.putArray("tools").addObject().put("name","echo_probe").put("description","Read-only regression fixture. Returns a unique verification token.");tool.putObject("inputSchema").put("type","object").putObject("properties");tool.putObject("annotations").put("readOnlyHint",true);}
                case "tools/call" -> {called.incrementAndGet();header.set(exchange.getRequestHeaders().getFirst("X-Probe-Key"));result.put("isError",false).putArray("content").addObject().put("type","text").put("text",mcpToken);}
                default -> {response.remove("result");response.putObject("error").put("code",-32601).put("message","Method not supported by fixture");}
            }
            byte[] bytes=json.writeValueAsBytes(response);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            var mcp=new com.myharness.agent.entity.dto.McpRuntimeDTO();mcp.setServerCode("tool-probe");mcp.setTransportType("STREAMABLE_HTTP");mcp.setUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/mcp");mcp.setHttpHeaders(Map.of("X-Probe-Key","synthetic-probe-key"));
            var properties=new AgentProperties();properties.setDataDir(data);properties.setResponsesHistoryCompatibility(true);
            var local=new ModelRuntimeDTO();local.setSchemaVersion(2);local.setRuntimeMode("LOCAL_CODEX");local.setRuntimeKey("e".repeat(64));
            try(var adapter=new AppServerCodexAdapter(properties,json)) {
                String id=adapter.startThread(new CodexThreadOptions("local-tool-probe",workspace,local).withExpertRuntime(List.of(),List.of(mcp)));
                var finished=new CompletableFuture<String>();
                adapter.startTurn(id,new CodexTurnInput("This isolated workspace is a regression fixture. Use exec_command with Get-Content to read probe.txt. Then use apply_patch to create verified.txt containing exactly the same token plus a newline. Call the read-only MCP echo_probe tool once. Use no other MCP or web tools. Finally report both tokens, without making further changes."),new CodexEventListener() {
                    public void onEvent(CodexEvent event) { }
                    public void onApproval(CodexApproval approval) {finished.completeExceptionally(new AssertionError("Unexpected approval"));}
                    public void onCompleted(String turnId,String status,String reason) {finished.complete(status);}
                });
                assertEquals("completed",finished.get(120,TimeUnit.SECONDS));
                assertEquals(fileToken,Files.readString(workspace.resolve("verified.txt")).trim(),"Patch must contain the token actually read from disk");
                assertEquals(1,called.get());assertEquals("synthetic-probe-key",header.get());
                var result=(com.fasterxml.jackson.databind.JsonNode)ReflectionTestUtils.invokeMethod(adapter,"request","thread/read",json.createObjectNode().put("threadId",id).put("includeTurns",false));
                var rows=Files.readAllLines(Path.of(result.path("thread").path("path").asText())).stream().map(line->{try{return json.readTree(line);}catch(Exception e){throw new RuntimeException(e);}}).toList();
                var outputs=rows.stream().map(row->row.path("payload")).filter(p->p.path("type").asText().endsWith("tool_call_output") || "function_call_output".equals(p.path("type").asText())).toList();
                assertTrue(outputs.stream().anyMatch(p->p.path("output").toString().contains(fileToken)),"Shell must return the file content");
                assertTrue(outputs.stream().anyMatch(p->p.path("output").toString().contains(mcpToken)),"MCP result must reach Codex");
                for(var p:outputs) ToolExecutionAssertions.successful(p.path("output"));
                assertTrue(rows.stream().anyMatch(row->row.path("payload").path("input").asText().contains("apply_patch") || "apply_patch".equals(row.path("payload").path("name").asText())),"Must use the patch tool");
            }
        } finally {server.stop(0);}
    }
}
