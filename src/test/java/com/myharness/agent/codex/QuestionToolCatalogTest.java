package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class QuestionToolCatalogTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void removesOnlyAsyncQuestionsAndPreservesOriginalCatalog(@TempDir Path root) throws Exception {
        String source="""
                {"models":[{"slug":"native-model","context_window":400000,"tool_mode":"code_mode_only",
                "model_messages":{"instructions_template":"original instructions"},"unknown_future_field":true,
                "experimental_supported_tools":["send_user_message_async","clock","request_user_input_async","other"]}]}
                """;
        Path original=Files.writeString(root.resolve("models_cache.json"),source);
        var catalog=new QuestionToolCatalog(json);
        Path projected=catalog.project(original,root.resolve("agent"));
        var expected=json.readTree(source);
        ((com.fasterxml.jackson.databind.node.ObjectNode)expected.path("models").get(0)).putArray("experimental_supported_tools").add("clock").add("other");
        assertEquals(expected,json.readTree(projected.toFile()));
        assertEquals(source,Files.readString(original));
        assertEquals(projected,catalog.project(original,root.resolve("agent")));
        assertEquals(original,catalog.localSource(json.createObjectNode(),"native-model",root));
        assertThrows(CodexException.class,()->catalog.localSource(json.createObjectNode(),"missing",root));
    }
    @Test void honorsCustomCatalogAndRefusesMissingOrMalformedCapabilities(@TempDir Path root) throws Exception {
        var catalog=new QuestionToolCatalog(json);
        Path custom=Files.writeString(root.resolve("custom.json"),"{\"models\":[{\"slug\":\"custom\",\"experimental_supported_tools\":[]}]}");
        assertEquals(custom,catalog.localSource(json.createObjectNode().put("model_catalog_json",custom.toString()),"custom",root));
        assertThrows(CodexException.class,()->catalog.localSource(json.createObjectNode(),"custom",root));
        Files.writeString(custom,"{\"models\":[{\"slug\":\"custom\",\"experimental_supported_tools\":\"send_user_message_async\"}]}");
        assertThrows(CodexException.class,()->catalog.project(custom,root.resolve("agent")));
    }

    @Test void removesModeTemplatesThatOverridePerTurnExpertInstructionsEvenWithoutToolMetadata(@TempDir Path root) throws Exception {
        String source="""
                {"models":[{"slug":"native-model","model_messages":{
                  "instructions_template":"preserve native identity",
                  "collaboration_modes":{"default":"ignore per-turn instructions","plan":null},
                  "permissions":{"marker":"preserve isolation"}},"unknown_future_field":true}]}
                """;
        Path original=Files.writeString(root.resolve("models.json"),source);
        Path projected=new QuestionToolCatalog(json).project(original,root.resolve("agent"));
        var expected=json.readTree(source);
        ((com.fasterxml.jackson.databind.node.ObjectNode)expected.path("models").get(0).path("model_messages")).remove("collaboration_modes");
        assertEquals(expected,json.readTree(projected.toFile()));
        assertEquals(source,Files.readString(original));
    }
}
