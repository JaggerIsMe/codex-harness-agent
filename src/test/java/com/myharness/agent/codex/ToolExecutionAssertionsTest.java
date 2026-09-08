package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ToolExecutionAssertionsTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void rejectsActivityAndModelSuccessWithoutActualToolResults() {
        var activity=json.createObjectNode();activity.putObject("payload").put("type","webSearch").put("text","HISTORY_OK https://example.com");
        assertThrows(AssertionError.class,()->ToolExecutionAssertions.webResults(List.of(activity),false));
    }
    @Test void rejectsTheOriginalBridge404EvenIfAnotherAnswerContainsSources() {
        assertThrows(AssertionError.class,()->ToolExecutionAssertions.webResults(rows("search_query","Script error: Fatal error: http 404 Unsupported compatibility bridge route https://example.com"),false));
    }
    @Test void rejectsMissingOutputAndMissingForecast() {
        assertThrows(AssertionError.class,()->ToolExecutionAssertions.webResults(rows("search_query","https://example.com").subList(0,1),false));
        assertThrows(AssertionError.class,()->ToolExecutionAssertions.webResults(rows("search_query","https://example.com"),true));
    }
    @Test void acceptsCompletedSearchAndForecastResults() {
        var rows=new ArrayList<>(rows("search_query","Script completed: https://example.com/documentation"));
        rows.addAll(rows("weather","Script completed: forecast, 28°C"));
        assertDoesNotThrow(()->ToolExecutionAssertions.webResults(rows,true));
    }
    private List<JsonNode> rows(String kind,String result) {
        var call=json.createObjectNode();call.putObject("payload").put("type","custom_tool_call").put("call_id",kind).put("input","text(await tools.web__run({"+kind+":[{}]}));");
        var output=json.createObjectNode();output.putObject("payload").put("type","custom_tool_call_output").put("call_id",kind).putArray("output").addObject().put("type","input_text").put("text",result);
        return List.of(call,output);
    }
}
