package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Checks completed tool outputs, never model self-reports or activity notifications. */
final class ToolExecutionAssertions {
    static void webResults(List<JsonNode> rows, boolean requireWeather) {
        var outputs=new HashMap<String,JsonNode>();
        for(var row:rows) {
            var p=row.path("payload");
            if("custom_tool_call_output".equals(p.path("type").asText())) outputs.put(p.path("call_id").asText(),p.path("output"));
        }
        int searches=0,weather=0;
        for(var row:rows) {
            var p=row.path("payload");String code=p.path("input").asText();
            if(!"custom_tool_call".equals(p.path("type").asText()) || !code.contains("tools.web__run(")) continue;
            JsonNode output=outputs.get(p.path("call_id").asText());
            assertNotNull(output,"Every web invocation must have a result");
            successful(output);
            String text=output.toString();
            if(code.contains("search_query")) {
                searches++;
                assertTrue(text.contains("https://"),"Search must return actual source URLs");
            }
            if(code.contains("weather")) {
                weather++;
                assertTrue(text.contains("°") || text.contains("temperature") || text.contains("forecast"),"Weather must return forecast data");
            }
        }
        assertTrue(searches>0,"Must execute standalone search; an activity event is insufficient");
        if(requireWeather) assertTrue(weather>0,"Must execute the weather query");
    }
    static void successful(JsonNode output) {
        assertFalse(output.toString().matches("(?s).*(Script failed|Script error:|Fatal error:|Found no tool response|Unsupported compatibility bridge route).*"),"Tool invocation failed (payload omitted)");
    }
}
