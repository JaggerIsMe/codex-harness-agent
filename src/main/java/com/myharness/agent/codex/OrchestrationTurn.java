package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myharness.agent.workspace.*;
import java.util.*;

/** Per-Turn control evidence; the original node baseline survives user continuations. */
final class OrchestrationTurn {
    private final WorkspaceRegistry registry;
    private final String workspace;
    private final Map<String,String> baseline=new LinkedHashMap<>();
    private JsonNode outcome;
    private String inputError;
    String inputError(){return inputError;}
    OrchestrationTurn(JsonNode config,WorkspaceRegistry registry,String workspace) {
        this.registry=registry;this.workspace=workspace;
        if(config.path("protocol").asInt()!=1 || !config.path("outputs").isArray() || config.path("outputs").size()>20)
            throw new CodexException("Unsupported orchestration protocol");
        for(var file:config.path("outputs")) {
            String path=file.path("path").asText();
            if(path.isBlank() || baseline.containsKey(path))throw new CodexException("Invalid output reference");
            String previous=file.path("before").asText(null);
            if(previous!=null && !previous.matches("MISSING|EMPTY|UNVERIFIED|[0-9a-f]{64}"))throw new CodexException("Invalid output baseline");
            baseline.put(path,previous==null?WorkflowFileEvidence.fingerprint(registry,workspace,path):previous);
        }
        if(config.has("inputs") && (!config.path("inputs").isArray() || config.path("inputs").size()>20))throw new CodexException("Invalid input references");
        for(var file:config.path("inputs")) {
            String path=file.path("path").asText();
            String actual=WorkflowFileEvidence.fingerprint(registry,workspace,path);
            if(!actual.matches("[0-9a-f]{64}") || file.has("expected") && !actual.equals(file.path("expected").asText())) {
                inputError="输入文件不存在、无法安全读取或已偏离上游交接版本："+path;
                outcome=new ObjectMapper().createObjectNode().put("state","WAITING_USER").put("summary",inputError);break;
            }
        }
    }
    void report(JsonNode value) {OrchestrationOutcomeTool.validate(value);outcome=value.deepCopy();}
    ObjectNode finish() {
        var result=new ObjectMapper().createObjectNode().put("protocol",1);
        result.put("state",outcome==null?"MISSING":outcome.path("state").asText());
        if(outcome!=null)result.put("summary",outcome.path("summary").asText());
        var files=result.putArray("files");
        baseline.forEach((path,before)->files.addObject().put("path",path).put("before",before)
            .put("after",WorkflowFileEvidence.fingerprint(registry,workspace,path)));
        return result;
    }
}
