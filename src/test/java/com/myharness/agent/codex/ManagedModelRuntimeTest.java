package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myharness.agent.entity.dto.ModelRuntimeDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
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

    private ModelRuntimeDTO runtime(String suffix,String secret){ModelRuntimeDTO r=new ModelRuntimeDTO();r.setRuntimeKey(String.valueOf(suffix).repeat(64));r.setModelId("DeepSeek-V4-Flash-Vision-Exp");r.setProviderName("DeepSeek");r.setBaseUrl("https://models.example/v1");r.setApiKey(secret);r.setInputModalities(List.of("TEXT","IMAGE"));return r;}
    private static final class FakeGateway implements CodexGateway {
        private final String id;private String resumed;private boolean closed;FakeGateway(String id){this.id=id;}
        public String startThread(CodexThreadOptions o){return id;}public void resumeThread(String id,CodexThreadOptions o){resumed=id;}
        public String startTurn(String i,CodexTurnInput t,CodexEventListener l){return "turn";}public void interruptTurn(String a,String b){}public void resolveApproval(String a,com.myharness.agent.entity.enums.ApprovalDecision b){}public void closeThread(String id){}public boolean isAvailable(){return true;}public void close(){closed=true;}
    }
}
