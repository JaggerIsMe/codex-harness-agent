package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.entity.dto.ModelRuntimeDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Uses installed Local Codex metadata only; never starts a Turn or sends a model request. */
@EnabledIfSystemProperty(named="codex.local-catalog.smoke",matches="true")
class LocalQuestionCatalogSmokeTest {
    @Test void configuresInstalledLocalCatalogWithoutModelRequests(@TempDir Path workspace,@TempDir Path data) throws Exception {
        var json=new ObjectMapper();var properties=new AgentProperties();properties.setDataDir(data);
        properties.setResponsesHistoryCompatibility(false);
        var runtime=new ModelRuntimeDTO();runtime.setSchemaVersion(2);runtime.setRuntimeMode("LOCAL_CODEX");runtime.setRuntimeKey("local-question-catalog-smoke");
        var options=new CodexThreadOptions("local-question-catalog-smoke",workspace,runtime).withExpertRuntime(List.of(),List.of());
        try(var adapter=new AppServerCodexAdapter(properties,json)) {
            assertNotNull(adapter.startThread(options));
            assertNotNull(adapter.startupModelCatalog());
        }
        try(var files=java.nio.file.Files.list(data.resolve("model-catalogs"))) {
            var catalogs=files.filter(path->path.getFileName().toString().startsWith("questions-")).toList();
            assertFalse(catalogs.isEmpty());
            for(Path file:catalogs)for(var model:json.readTree(file.toFile()).path("models"))for(var tool:model.path("experimental_supported_tools"))
                assertFalse(List.of("request_user_input_async","send_user_message_async").contains(tool.asText()));
        }
    }
}
