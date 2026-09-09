package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.entity.dto.ModelRuntimeDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ManagedModelRuntimeTest {
    @TempDir Path workspace;

    @Test void writesResponsesProviderWithoutEmbeddingTheSecret() {
        ObjectMapper json=new ObjectMapper();ObjectNode params=json.createObjectNode();
        AppServerCodexAdapter adapter=new AppServerCodexAdapter(new com.myharness.agent.config.AgentProperties(),json);
        adapter.configureModelProvider(params,new CodexThreadOptions("project",workspace,runtime("a","secret-value")));
        assertEquals("harness_managed",params.path("config").path("model_provider").asText());
        var provider=params.path("config").path("model_providers").path("harness_managed");
        assertEquals("responses",provider.path("wire_api").asText());
        assertEquals("HARNESS_MODEL_API_KEY",provider.path("env_key").asText());
        assertFalse(params.toString().contains("secret-value"));
    }

    @Test void changedRuntimeReplacesProcessAndResumesSameThread() {
        AtomicInteger created=new AtomicInteger();List<FakeGateway> instances=new ArrayList<>();
        ConversationCodexGateway gateway=new ConversationCodexGateway(()->{FakeGateway value=new FakeGateway("thread-1");instances.add(value);created.incrementAndGet();return value;});
        try {
            String id=gateway.startThread(new CodexThreadOptions("project",workspace,runtime("a","one")));
            gateway.resumeThread(id,new CodexThreadOptions("project",workspace,runtime("b","two")));
            assertEquals(2,created.get());assertTrue(instances.get(0).closed);assertEquals("thread-1",instances.get(1).resumed);
        } finally {gateway.close();}
    }

    @Test void switchesManagedToLocalAndBackByReplacingOnlyTheProcess() {
        AtomicInteger created=new AtomicInteger();List<FakeGateway> instances=new ArrayList<>();
        ConversationCodexGateway gateway=new ConversationCodexGateway(()->{FakeGateway value=new FakeGateway("thread-1");instances.add(value);created.incrementAndGet();return value;});
        try {
            String id=gateway.startThread(new CodexThreadOptions("project",workspace,runtime("a","one")));
            gateway.resumeThread(id,new CodexThreadOptions("project",workspace,localRuntime()));
            gateway.resumeThread(id,new CodexThreadOptions("project",workspace,runtime("b","two")));
            assertEquals(3,created.get());assertEquals("thread-1",instances.get(1).resumed);assertEquals("thread-1",instances.get(2).resumed);
            assertTrue(instances.get(0).closed);assertTrue(instances.get(1).closed);
        } finally {gateway.close();}
    }

    @Test void localTargetUsesConfiguredModelOrAUniqueCodexDefault() {
        ObjectMapper json=new ObjectMapper();ObjectNode config=json.createObjectNode();
        config.putObject("config").put("model_provider","openai").put("model","gpt-configured");
        ObjectNode models=json.createObjectNode();models.putArray("data").addObject().put("model","gpt-configured").put("isDefault",false);
        var configured=AppServerCodexAdapter.selectLocalModelTarget(config,models);
        assertEquals("openai",configured.provider());assertEquals("gpt-configured",configured.model());

        ((ObjectNode)config.path("config")).remove("model");
        models.withArray("data").addObject().put("model","gpt-default").put("isDefault",true);
        assertEquals("gpt-default",AppServerCodexAdapter.selectLocalModelTarget(config,models).model());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void repeatedSwitchesUseFreshProcessesWithoutManagedCredentialsOrCatalogInLocalCodex() throws Exception {
        Path recording=workspace.resolve("app-server-recording.jsonl");
        Path arguments=workspace.resolve("fake-app-server.args");
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        Files.writeString(arguments,"-cp\n"+javaArgument(classpath)+"\n"+FixtureAppServer.class.getName()+"\n"
                +javaArgument(recording.toString())+"\n"+javaArgument(workspace.toString())+"\n");
        Path command=workspace.resolve("fake-codex.cmd");
        Path java=Path.of(System.getProperty("java.home"),"bin","java.exe");
        Files.writeString(command,"@echo off\r\n\""+java+"\" \"@"+arguments+"\" %*\r\n");
        AgentProperties properties=new AgentProperties();properties.setCodexCommand(command.toString());
        properties.setDataDir(workspace.resolve("agent-data"));properties.setCodexRequestTimeoutSeconds(10);
        properties.setResponsesHistoryCompatibility(false);
        ObjectMapper mapper=new ObjectMapper();

        try(var gateway=new ConversationCodexGateway(()->new AppServerCodexAdapter(properties,mapper))) {
            String thread=gateway.startThread(new CodexThreadOptions("project",workspace,runtime("a","fixture-key-a")));
            gateway.resumeThread(thread,new CodexThreadOptions("project",workspace,localRuntime()));
            gateway.resumeThread(thread,new CodexThreadOptions("project",workspace,runtime("b","fixture-key-b")));
            gateway.resumeThread(thread,new CodexThreadOptions("project",workspace,localRuntime()));
            assertEquals("fixture-thread",thread);

            List<JsonNode> records=new ArrayList<>();
            for(String line:Files.readAllLines(recording)) records.add(mapper.readTree(line));
            var startups=records.stream().filter(value->"startup".equals(value.path("type").asText())).toList();
            assertEquals(4,startups.size());
            assertEquals(4,startups.stream().map(value->value.path("pid").asLong()).distinct().count());
            for(int index=0;index<startups.size();index++) {
                JsonNode startup=startups.get(index);boolean managed=index%2==0;
                assertEquals(managed,startup.path("managedCredentialPresent").asBoolean());
                assertEquals(managed,startup.path("managedCatalogPresent").asBoolean());
                if(index<3) assertFalse(ProcessHandle.of(startup.path("pid").asLong()).map(ProcessHandle::isAlive).orElse(false));
            }
            var activations=records.stream().filter(value->"thread/start".equals(value.path("method").asText())
                    || "thread/resume".equals(value.path("method").asText())).toList();
            assertEquals(List.of("thread/start","thread/resume","thread/resume","thread/resume"),
                    activations.stream().map(value->value.path("method").asText()).toList());
            for(int index=0;index<activations.size();index++) {
                JsonNode activation=activations.get(index);boolean managed=index%2==0;
                assertEquals("fixture-thread",activation.path("threadId").asText());
                assertEquals(managed?"harness_managed":"openai",activation.path("modelProvider").asText());
                assertEquals(managed?"DeepSeek-V4-Flash-Vision-Exp":"gpt-local",activation.path("model").asText());
                assertEquals(managed,activation.path("managedProviderConfigured").asBoolean());
            }
        }
    }

    private static String javaArgument(String value) {return "\""+value.replace('\\','/').replace("\"","\\\"")+"\"";}

    /** A local JSON-RPC process fixture: it never reads credentials or makes upstream requests. */
    public static final class FixtureAppServer {
        public static void main(String[] args) throws Exception {
            Path recording=Path.of(args[0]);String cwd=args[1];ObjectMapper mapper=new ObjectMapper();
            boolean catalog=java.util.Arrays.stream(args).anyMatch(value->value.startsWith("model_catalog_json="));
            append(recording,mapper.createObjectNode().put("type","startup").put("pid",ProcessHandle.current().pid())
                    .put("managedCredentialPresent",System.getenv().containsKey("HARNESS_MODEL_API_KEY"))
                    .put("managedCatalogPresent",catalog));
            try(var input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))) {
                String line;
                while((line=input.readLine())!=null) {
                    JsonNode request=mapper.readTree(line);if(!request.has("id")) continue;
                    String method=request.path("method").asText();JsonNode params=request.path("params");
                    ObjectNode result=mapper.createObjectNode();
                    switch(method) {
                        case "initialize" -> { }
                        case "config/read" -> result.putObject("config").put("model_provider","openai").put("model","gpt-local");
                        case "model/list" -> result.putArray("data").addObject().put("model","gpt-local").put("isDefault",true);
                        case "thread/read" -> result.putObject("thread").put("id","fixture-thread").put("cwd",cwd)
                                .putObject("status").put("type","idle");
                        case "thread/start","thread/resume" -> {
                            String threadId=params.path("threadId").asText("fixture-thread");
                            result.put("model",params.path("model").asText()).put("modelProvider",params.path("modelProvider").asText());
                            result.putObject("activePermissionProfile").put("id",params.path("permissions").asText());
                            result.putObject("thread").put("id",threadId).put("cwd",cwd).putObject("status").put("type","idle");
                            append(recording,mapper.createObjectNode().put("method",method).put("threadId",threadId)
                                    .put("model",params.path("model").asText()).put("modelProvider",params.path("modelProvider").asText())
                                    .put("managedProviderConfigured",params.path("config").path("model_providers").has("harness_managed")));
                        }
                        default -> throw new IllegalArgumentException("Unexpected fixture RPC: "+method);
                    }
                    ObjectNode response=mapper.createObjectNode().put("jsonrpc","2.0");
                    response.set("id",request.get("id"));response.set("result",result);
                    System.out.println(mapper.writeValueAsString(response));System.out.flush();
                }
            }
        }
        private static void append(Path recording,ObjectNode value) throws Exception {
            Files.writeString(recording,value+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        }
    }

    @Test void customModelProcessLoadsACompleteMetadataCatalog() throws Exception {
        ModelRuntimeDTO runtime=runtime("c","secret");runtime.setContextWindowTokens(128000);
        Path catalog=new ManagedModelCatalog(new ObjectMapper()).write(workspace,runtime);
        var parsed=new ObjectMapper().readTree(Files.readString(catalog)).path("models").get(0);
        assertEquals("DeepSeek-V4-Flash-Vision-Exp",parsed.path("slug").asText());
        assertEquals(128000,parsed.path("context_window").asInt());
        assertEquals(List.of("text","image"),new ObjectMapper().convertValue(parsed.path("input_modalities"),new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){}));
        assertEquals("unified_exec",parsed.path("shell_type").asText());
        assertEquals("tokens",parsed.path("truncation_policy").path("mode").asText());
        assertFalse(Files.readString(catalog).contains("secret"));
        List<String> command=CodexProcessCommand.appServer("C:\\codex.exe",true,"elevated","Windows 11","","cmd.exe",catalog);
        assertTrue(String.join(" ",command).contains("model_catalog_json"));
    }

    private ModelRuntimeDTO runtime(String suffix,String secret){ModelRuntimeDTO r=new ModelRuntimeDTO();r.setSchemaVersion(2);r.setRuntimeMode("MANAGED_PROVIDER");r.setConfigurationVersionId(1L);r.setRuntimeKey(String.valueOf(suffix).repeat(64));r.setModelId("DeepSeek-V4-Flash-Vision-Exp");r.setProviderName("DeepSeek");r.setBaseUrl("https://models.example/v1");r.setApiKey(secret);r.setInputModalities(List.of("TEXT","IMAGE"));return r;}
    private ModelRuntimeDTO localRuntime(){ModelRuntimeDTO r=new ModelRuntimeDTO();r.setSchemaVersion(2);r.setRuntimeMode("LOCAL_CODEX");r.setRuntimeKey("l".repeat(64));return r;}
    private static final class FakeGateway implements CodexGateway {
        private final String id;private String resumed;private boolean closed;FakeGateway(String id){this.id=id;}
        public String startThread(CodexThreadOptions o){return id;}public void resumeThread(String id,CodexThreadOptions o){resumed=id;}
        public String startTurn(String i,CodexTurnInput t,CodexEventListener l){return "turn";}public void interruptTurn(String a,String b){}public void resolveApproval(String a,com.myharness.agent.entity.enums.ApprovalDecision b){}public void closeThread(String id){}public boolean isAvailable(){return true;}public void close(){closed=true;}
    }
}
