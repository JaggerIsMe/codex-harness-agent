package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myharness.agent.entity.dto.ModelRuntimeDTO;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

final class ManagedModelCatalog {
    private static final String BASE_INSTRUCTIONS = "You are Codex, a coding agent. Follow the user's instructions and the repository's AGENTS.md files. Use tools carefully, preserve unrelated work, and communicate results clearly.";
    private static final int MIN_CONTEXT_WINDOW = 1024;
    private static final int MAX_CONTEXT_WINDOW = 2_000_000;
    private final ObjectMapper objectMapper;

    ManagedModelCatalog(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    Path write(Path directory, ModelRuntimeDTO runtime) {
        validate(directory,runtime);
        Path target=directory.resolve(runtime.getRuntimeKey()+".json").toAbsolutePath().normalize();
        Path temporary=null;
        try {
            Files.createDirectories(directory);
            temporary=Files.createTempFile(directory,"model-catalog-",".tmp");
            objectMapper.writeValue(temporary.toFile(),catalog(runtime));
            move(temporary,target);
            return target;
        } catch (IOException exception) {
            throw new CodexException("Unable to write managed model metadata catalog",exception);
        } finally {
            if(temporary!=null) try {Files.deleteIfExists(temporary);} catch(IOException ignored) { }
        }
    }

    private ObjectNode catalog(ModelRuntimeDTO runtime) {
        ObjectNode root=objectMapper.createObjectNode();
        ArrayNode models=root.putArray("models");
        ObjectNode model=models.addObject();
        model.put("slug",runtime.getModelId());
        model.put("display_name",hasText(runtime.getName())?runtime.getName():runtime.getModelId());
        model.put("description","Harness managed third-party model");
        model.putNull("default_reasoning_level");
        model.putArray("supported_reasoning_levels");
        model.put("shell_type","unified_exec");
        model.put("visibility","list");
        model.put("supported_in_api",true);
        model.put("priority",1);
        model.put("support_verbosity",false);
        ObjectNode truncation=model.putObject("truncation_policy");
        truncation.put("mode","tokens");
        truncation.put("limit",Math.min(10_000,Math.max(MIN_CONTEXT_WINDOW,runtime.getContextWindowTokens()/10)));
        model.putArray("experimental_supported_tools");
        model.put("base_instructions",BASE_INSTRUCTIONS);
        model.put("context_window",runtime.getContextWindowTokens());
        ArrayNode modalities=model.putArray("input_modalities");
        inputModalities(runtime).forEach(modalities::add);
        return root;
    }

    private Set<String> inputModalities(ModelRuntimeDTO runtime) {
        Set<String> result=new LinkedHashSet<>();
        result.add("text");
        runtime.getInputModalities().stream().filter(this::hasText)
                .map(value->value.trim().toLowerCase(Locale.ROOT))
                .filter(value->Set.of("text","image").contains(value))
                .forEach(result::add);
        return result;
    }

    private void validate(Path directory,ModelRuntimeDTO runtime) {
        if(directory==null) throw new CodexException("Model catalog directory must be configured");
        if(runtime==null||!hasText(runtime.getModelId())) throw new CodexException("Managed model ID must be configured");
        if(!hasText(runtime.getRuntimeKey())||!runtime.getRuntimeKey().matches("[a-fA-F0-9]{64}"))
            throw new CodexException("Managed model runtime key is invalid");
        if(runtime.getContextWindowTokens()<MIN_CONTEXT_WINDOW||runtime.getContextWindowTokens()>MAX_CONTEXT_WINDOW)
            throw new CodexException("Managed model context window is invalid");
    }

    private void move(Path source,Path target) throws IOException {
        try {Files.move(source,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException ignored){Files.move(source,target,StandardCopyOption.REPLACE_EXISTING);}
    }

    private boolean hasText(String value) {
        return value!=null&&!value.trim().isEmpty();
    }
}
