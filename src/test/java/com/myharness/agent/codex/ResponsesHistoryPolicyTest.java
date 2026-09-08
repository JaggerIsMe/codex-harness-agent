package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ResponsesHistoryPolicyTest {
    @TempDir Path data;
    @TempDir Path workspace;
    private final ObjectMapper json=new ObjectMapper();
    private final String id=UUID.randomUUID().toString();
    private ResponsesHistoryPolicy policy(String target) {return new ResponsesHistoryPolicy(data,workspace,id,target,json);}
    private JsonNode reasoning() throws Exception {return json.readTree("{\"type\":\"reasoning\",\"id\":\"r1\",\"encrypted_content\":\"synthetic-provider-secret\",\"summary\":[],\"content\":[{\"type\":\"reasoning_text\",\"text\":\"synthetic thought\"}]}");}
    private JsonNode request(JsonNode item) {
        var request=json.createObjectNode().put("model","test-model");request.putArray("input").add(item);return request;
    }
    private JsonNode done(JsonNode item) {var event=json.createObjectNode().put("type","response.output_item.done");event.set("item",item);return event;}

    @Test void preservesNativeReasoningAcrossRestartButExcludesItForAnotherTarget() throws Exception {
        JsonNode item=reasoning(),request=request(item),original=request.deepCopy();
        try(var a=policy("a")) {
            assertEquals(1,a.project(request).excludedReasoning());
            a.observe(done(item));assertEquals(0,a.project(request).excludedReasoning());
        }
        try(var b=policy("b")) {assertEquals(1,b.project(request).excludedReasoning());}
        try(var a=policy("a")) {
            assertEquals(original,a.project(request).request());
            com.fasterxml.jackson.databind.node.ObjectNode withoutId=item.deepCopy();withoutId.remove("id");
            assertEquals(0,a.project(request(withoutId)).excludedReasoning());
        }
        assertEquals(original,request);
        try(var files=Files.walk(data)) {
            for(Path file:files.filter(p->p.toString().endsWith(".json")).toList()) {
                String text=Files.readString(file);assertFalse(text.contains("synthetic-provider-secret"));assertFalse(text.contains("synthetic thought"));
            }
        }
    }
    @Test void preservesMessageAndToolCallPairVerbatim() throws Exception {
        var value=json.readTree("{\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":\"hello\"},{\"type\":\"function_call\",\"name\":\"tool\",\"call_id\":\"c\",\"arguments\":\"{}\"},{\"type\":\"function_call_output\",\"call_id\":\"c\",\"output\":\"tool result\"}]}");
        try(var policy=policy("a")) {assertEquals(value,policy.project(value).request());}
    }
    @ParameterizedTest @ValueSource(strings={
        "{\"type\":\"function_call_output\",\"call_id\":\"orphan\",\"output\":\"value\"}",
        "{\"type\":\"function_call\",\"name\":\"tool\",\"call_id\":\"unfinished\",\"arguments\":\"{}\"}",
        "{\"type\":\"message\",\"role\":\"unknown\",\"content\":\"text\"}",
        "{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_file\",\"file_id\":\"provider-id\"}]}",
        "{\"type\":\"web_search_call\",\"id\":\"provider-id\"}",
        "{\"type\":\"compaction\",\"encrypted_content\":\"provider-opaque-state\"}",
        "{\"type\":\"unknown_future_type\"}"
    }) void unsupportedHistoryIsNotSilentlyDiscarded(String item) throws Exception {
        try(var policy=policy("a")) {assertThrows(CodexException.class,()->policy.project(request(json.readTree(item))));}
    }
    @Test void rejectsRemoteHistoryHandlesAndInvalidInput() throws Exception {
        try(var policy=policy("a")) {
            assertThrows(CodexException.class,()->policy.project(json.readTree("{\"input\":[],\"previous_response_id\":\"old-provider\"}")));
            assertThrows(CodexException.class,()->policy.project(json.readTree("{\"input\":[],\"conversation\":\"old-provider\"}")));
            assertThrows(CodexException.class,()->policy.project(json.readTree("{\"input\":\"text\"}")));
        }
    }
    @Test void knownNativeCompactionCanContinueButCannotCrossProviders() throws Exception {
        var item=json.readTree("{\"type\":\"compaction\",\"encrypted_content\":\"opaque-state\"}");
        try(var policy=policy("a")) {policy.observe(done(item));assertEquals(request(item),policy.project(request(item)).request());}
        try(var policy=policy("b")) {assertThrows(CodexException.class,()->policy.project(request(item)));}
    }
    @Test void locksThreadAcrossTargetsAndRejectsProjectLocalMetadata() {
        try(var policy=policy("a")) {assertThrows(CodexException.class,()->policy("b"));}
        try(var policy=policy("b")) {assertNotNull(policy);}
        assertThrows(CodexException.class,()->new ResponsesHistoryPolicy(workspace.resolve("data"),workspace,id,"a",json));
    }
    @Test void corruptProvenanceFailsClosed() throws Exception {
        try(var policy=policy("a")) {policy.observe(done(reasoning()));}
        Path manifest=data.resolve("responses-history").resolve(id).resolve(ResponsesHistoryPolicy.digest("a")+".json");
        Files.writeString(manifest,"invalid json");assertThrows(CodexException.class,()->policy("a"));
    }
}
