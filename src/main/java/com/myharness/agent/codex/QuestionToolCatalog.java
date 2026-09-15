package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.Set;

/** Private Harness runtime projection; never changes the source model catalog or credentials. */
final class QuestionToolCatalog {
    private static final Set<String> ASYNC=Set.of("request_user_input_async","send_user_message_async");
    private final ObjectMapper json;
    QuestionToolCatalog(ObjectMapper json) {this.json=json;}

    Path localSource(JsonNode config,String model,Path codexHome) {
        String configured=config.path("model_catalog_json").asText(null);
        Path source=configured==null?codexHome.resolve("models_cache.json"):Path.of(configured);
        JsonNode catalog=read(source);
        for(var item:catalog.path("models")) if(model.equals(item.path("slug").asText()))return source;
        throw new CodexException("本地 Codex 模型目录不包含当前模型，无法安全配置等待用户回答的工具，请刷新 Codex 模型目录后重试");
    }

    Path project(Path source,Path directory) {
        ObjectNode catalog=read(source);
        for(var model:catalog.path("models")) {
            if(!model.isObject())throw new CodexException("Codex 模型目录格式无效");
            // Native mode templates override collaborationMode.settings.developer_instructions.
            // Harness supplies its expert/Skill/confirmation instructions on every Turn, so use
            // Codex's per-turn mode instructions instead. Preserve base instructions and permissions.
            if(model.path("model_messages") instanceof ObjectNode messages) messages.remove("collaboration_modes");
            JsonNode tools=model.path("experimental_supported_tools");
            if(tools.isMissingNode()||tools.isNull())continue;
            if(!tools.isArray())throw new CodexException("Codex 模型工具能力格式无效");
            var filtered=((ObjectNode)model).putArray("experimental_supported_tools");
            for(var tool:tools)if(!ASYNC.contains(tool.asText()))filtered.add(tool.deepCopy());
        }
        Path temporary=null;
        try {
            byte[] bytes=json.writeValueAsBytes(catalog);
            String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            Files.createDirectories(directory);
            Path target=directory.resolve("questions-"+hash+".json");
            temporary=Files.createTempFile(directory,"questions-",".tmp");
            Files.write(temporary,bytes);
            try {Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException failure) {Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
            return target.toAbsolutePath().normalize();
        } catch(Exception failure) {throw new CodexException("无法保存 Harness 提问工具能力目录",failure);}
        finally {if(temporary!=null)try {Files.deleteIfExists(temporary);}catch(java.io.IOException ignored) {}}
    }

    private ObjectNode read(Path path) {
        try {
            if(!Files.isRegularFile(path)||Files.size(path)>32*1024*1024)throw new java.io.IOException();
            JsonNode value=json.readTree(path.toFile());
            if(!(value instanceof ObjectNode object)||!value.path("models").isArray()||value.path("models").isEmpty())throw new java.io.IOException();
            return object;
        } catch(java.io.IOException failure) {throw new CodexException("无法读取本地 Codex 模型目录，已阻止暴露无法等待用户回答的工具，请刷新 Codex 模型目录后重试");}
    }

    static Path codexHome() {
        String configured=System.getenv("CODEX_HOME");
        return configured==null||configured.isBlank()?Path.of(System.getProperty("user.home"),".codex"):Path.of(configured);
    }
}
