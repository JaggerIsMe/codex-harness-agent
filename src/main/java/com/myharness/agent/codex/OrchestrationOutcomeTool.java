package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** A control-plane declaration. It never changes the user's message or grants permissions. */
final class OrchestrationOutcomeTool {
    static final String NAME="harness_node_outcome";
    static void configure(ObjectNode params) {
        var tool=params.withArray("dynamicTools").addObject().put("type","function").put("name",NAME)
            .put("description","Report this workflow node's outcome before ending the turn. Use WAITING_USER with the question when required information is missing; use COMPLETE only when the assigned work and outputs are ready. summary is the complete handoff result (preserve any user-required output format). Reporting COMPLETE does not bypass approval or output validation. Update the report if the outcome changes.");
        var schema=tool.putObject("inputSchema").put("type","object").put("additionalProperties",false);
        schema.putArray("required").add("state").add("summary");
        var fields=schema.putObject("properties");
        fields.putObject("state").put("type","string").putArray("enum").add("COMPLETE").add("WAITING_USER");
        fields.putObject("summary").put("type","string").put("minLength",1).put("maxLength",16000);
    }
    static void validate(JsonNode value) {
        if(value==null || !value.isObject() || value.size()!=2 || !value.path("state").isTextual()
            || !java.util.Set.of("COMPLETE","WAITING_USER").contains(value.path("state").asText())
            || !value.path("summary").isTextual() || value.path("summary").asText().isBlank() || value.path("summary").asText().length()>16000)
            throw new CodexException("Invalid node outcome");
    }
}
