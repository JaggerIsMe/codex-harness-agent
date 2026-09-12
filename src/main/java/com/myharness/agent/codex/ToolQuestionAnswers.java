package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.HashSet;
import java.util.Set;

/** Validates explicit user answers against the original request, never guessing a choice. */
final class ToolQuestionAnswers {
    private ToolQuestionAnswers() {}
    static ObjectNode validate(JsonNode params, JsonNode supplied) {
        JsonNode questions = params.path("questions");
        if (!questions.isArray() || questions.isEmpty() || questions.size() > 3)
            throw invalid("问题列表无效");
        if (supplied == null || !supplied.isObject() || supplied.size() != questions.size())
            throw invalid("请逐项选择方案或填写回答");
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        Set<String> ids = new HashSet<>();
        int total = 0;
        for (JsonNode question : questions) {
            String id = question.path("id").asText("");
            if (id.isBlank() || !ids.add(id)) throw invalid("问题标识为空或重复");
            JsonNode entry = supplied.path(id), values = entry.path("answers");
            if (!entry.isObject() || entry.size() != 1 || !values.isArray() || values.size() != 1
                    || !values.get(0).isTextual()) throw invalid("每个问题需要一个明确回答");
            String answer = values.get(0).asText();
            total += answer.length();
            if (answer.isBlank() || answer.length() > 4000 || total > 12000)
                throw invalid("回答为空或过长");
            JsonNode options = question.path("options");
            boolean offered = false;
            if (options.isArray()) for (JsonNode option : options)
                if (answer.equals(option.path("label").asText())) offered = true;
            if (options.isArray() && !options.isEmpty() && !question.path("isOther").asBoolean(false) && !offered)
                throw invalid("回答不是本题提供的选项");
            result.putObject(id).putArray("answers").add(answer);
        }
        return result;
    }
    private static RuntimeException invalid(String message) {
        return new com.myharness.agent.command.AgentOperationException("APPROVAL_INPUT_INVALID", message);
    }
}
