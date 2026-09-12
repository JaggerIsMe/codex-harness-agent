package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToolQuestionAnswersTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void acceptsExactLabelsAndExplicitOtherButRejectsMissingAndInventedAnswers() throws Exception {
        var question=json.readTree("{\"questions\":[{\"id\":\"method\",\"options\":[{\"label\":\"两个都要\"}]}]}");
        var exact=json.readTree("{\"method\":{\"answers\":[\"两个都要\"]}}");
        assertEquals(exact,ToolQuestionAnswers.validate(question,exact));
        assertThrows(RuntimeException.class,()->ToolQuestionAnswers.validate(question,null));
        assertThrows(RuntimeException.class,()->ToolQuestionAnswers.validate(question,json.readTree("{\"method\":{\"answers\":[\"Yes\"]}}")));
        assertThrows(RuntimeException.class,()->ToolQuestionAnswers.validate(question,json.readTree("{\"other\":{\"answers\":[\"两个都要\"]}}")));
        assertThrows(RuntimeException.class,()->ToolQuestionAnswers.validate(question,json.readTree("{\"method\":{\"answers\":[\"两个都要\",\"两个都要\"]}}")));
        ((com.fasterxml.jackson.databind.node.ObjectNode)question.path("questions").get(0)).put("isOther",true);
        var custom=json.readTree("{\"method\":{\"answers\":[\"我希望使用水彩风格 SVG\"]}}");
        assertEquals(custom,ToolQuestionAnswers.validate(question,custom));
    }
    @Test void requiresEveryQuestionAndSupportsFreeTextWithoutOptions() throws Exception {
        var question=json.readTree("{\"questions\":[{\"id\":\"one\"},{\"id\":\"two\",\"isSecret\":true}]}");
        var answers=json.readTree("{\"one\":{\"answers\":[\"中文与 spaces\"]},\"two\":{\"answers\":[\"test-only\"]}}");
        assertEquals(answers,ToolQuestionAnswers.validate(question,answers));
        ((com.fasterxml.jackson.databind.node.ObjectNode)answers).remove("two");
        assertThrows(RuntimeException.class,()->ToolQuestionAnswers.validate(question,answers));
    }
}
