package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myharness.agent.config.AgentProperties;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "codex.smoke", matches = "true")
class CodexMcpInheritanceSmokeTest {
    @ParameterizedTest
    @ValueSource(strings = {"url = \"http://127.0.0.1:9/mcp\"", "command = \"unused-test-mcp\"", ""})
    void startsWithDisabledInheritedTransport(String transport, @TempDir Path directory) throws Exception {
        Path home=Files.createDirectory(directory.resolve("codex-home"));
        Path workspace=Files.createDirectory(directory.resolve("workspace"));
        // No MCP process or network connection is needed to exercise Codex's configuration loader.
        Files.writeString(home.resolve("config.toml"),
                transport.isEmpty()?"":"[mcp_servers.inherited]\n"+transport+"\nenabled = false\n",StandardCharsets.UTF_8);
        var mapper=new ObjectMapper();
        var properties=new AgentProperties();properties.setDataDir(directory.resolve("agent-data"));
        ObjectNode params=mapper.createObjectNode().put("cwd",workspace.toString()).put("approvalPolicy","never");
        var builder=new ProcessBuilder(CodexProcessCommand.appServer(System.getProperty("codex.smoke.command","codex")))
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("CODEX_HOME",home.toString());
        Process process=builder.start();
        var responses=new LinkedBlockingQueue<JsonNode>();
        Thread reader=Thread.ofVirtual().start(()-> {
            try(var output=new BufferedReader(new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8))) {
                String line;
                while((line=output.readLine())!=null) responses.add(mapper.readTree(line));
            } catch(Exception failure) {
                responses.add(mapper.createObjectNode().putObject("error").put("message",failure.toString()));
            }
        });
        var input=new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8);
        try {
            ObjectNode initialization=mapper.createObjectNode();
            initialization.putObject("clientInfo").put("name","harness-mcp-smoke").put("version","1.0");
            initialization.putObject("capabilities").put("experimentalApi",true);
            request(input,responses,mapper,1,"initialize",initialization);
            input.write("{\"method\":\"initialized\"}\n");input.flush();
            try(var adapter=new AppServerCodexAdapter(properties,mapper) {
                @Override JsonNode request(String method,JsonNode arguments) {
                    if("mcpServerStatus/list".equals(method)) {
                        // A discovered app service has no standard transport in config.toml.
                        ObjectNode result=mapper.createObjectNode();result.putNull("nextCursor");
                        result.putArray("data").addObject().put("name",transport.isEmpty()?"codex_app":"inherited")
                                .put("runtimeStatus","connected");
                        return result;
                    }
                    assertEquals("config/read",method);
                    try {return CodexMcpInheritanceSmokeTest.this.request(input,responses,mapper,100,method,arguments);}
                    catch(Exception failure) {throw new AssertionError(failure);}
                }
            }) {
                var options=new CodexThreadOptions("mcp-inheritance-probe",workspace,null).withExpertRuntime(List.of(),List.of());
                adapter.configureMcpServers(params,options);
                adapter.disableInheritedMcpServers(params,options);
            }
            JsonNode started=request(input,responses,mapper,2,"thread/start",params);
            String threadId=started.path("thread").path("id").asText();
            assertTrue(!threadId.isBlank());
        } finally {
            // Stop only this test's process tree so Windows can release the temporary workspace.
            List<ProcessHandle> children=process.descendants().toList();
            input.close();
            if(!process.waitFor(5,TimeUnit.SECONDS)) {process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}
            for(ProcessHandle child:children) if(child.isAlive()) child.destroyForcibly();
            for(ProcessHandle child:children) if(child.isAlive()) child.onExit().get(5,TimeUnit.SECONDS);
            reader.join(5000);
        }
    }

    private JsonNode request(OutputStreamWriter input,LinkedBlockingQueue<JsonNode> responses,ObjectMapper mapper,
                             int id,String method,JsonNode params) throws Exception {
        ObjectNode request=mapper.createObjectNode().put("id",id).put("method",method);request.set("params",params);
        input.write(mapper.writeValueAsString(request)+"\n");input.flush();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(true) {
            JsonNode response=responses.poll(Math.max(0,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
            assertNotNull(response,"Timed out waiting for "+method);
            if(response.path("id").asInt(-1)!=id) continue;
            assertTrue(response.has("result"),()->method+": "+CodexDiagnostics.redact(response.toString()));
            return response.path("result");
        }
    }
}
