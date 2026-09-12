package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.myharness.agent.entity.enums.ApprovalDecision;

/** User consent is tool input, never a grant of sandbox permissions. */
final class ExecutionConfirmation {
    static final String QUESTION_ID="harness-confirm-action";
    static final String INSTRUCTIONS="""
            Harness 执行确认规则：
            用户明确要求执行前审批/确认时，必须先调用 request_user_input 并等待用户决定。
            只提交一个问题，id 必须为 harness-confirm-action，header 为 执行前确认。
            question 必须明确描述本次操作、目标文件/工具/命令和影响范围。
            options 必须恰好为三个选项：批准本次、拒绝操作、拒绝并中断；分别说明继续本次操作、跳过本次操作、停止本轮执行。
            批准仅代表同意这次描述的操作，不授予新权限；继续使用原有项目沙箱和网络规则。
            用户拒绝或取消时不得执行该操作、不得通过替代命令绕过决定。不要用命令扩权请求代替执行确认。
            平台拒绝原生命令/文件审批意味着权限策略拦截，不是用户拒绝，必须如实说明。
            普通已授权操作无需逐步确认，只有用户要求或业务确需用户决定时才发起。
            """;

    static void configure(ObjectNode params) {
        params.withObject("config").withObject("features").put("default_mode_request_user_input",true);
        params.put("developerInstructions",INSTRUCTIONS);
    }

    static boolean isConfirmation(JsonNode params) {
        JsonNode questions=params.path("questions");
        return questions.isArray() && questions.size()==1 && QUESTION_ID.equals(questions.get(0).path("id").asText());
    }

    static void validate(JsonNode params) {
        JsonNode question=params.path("questions").path(0);
        if(question.path("question").asText().isBlank()) throw new CodexException("执行确认缺少操作说明");
        JsonNode options=question.path("options");
        java.util.Set<String> labels=new java.util.HashSet<>();
        if(options.isArray()) options.forEach(option -> labels.add(option.path("label").asText()));
        if(!options.isArray() || options.size()!=3 || !labels.equals(java.util.Set.of("批准本次","拒绝操作","拒绝并中断")))
            throw new CodexException("执行确认必须提供批准本次、拒绝操作、拒绝并中断三个选项");
    }

    static void answer(ObjectNode result,ApprovalDecision decision) {
        String label=switch(decision) {
            case ACCEPT -> "批准本次";
            case DECLINE -> "拒绝操作";
            case CANCEL -> "拒绝并中断";
            default -> throw new CodexException("执行确认仅支持本次决定");
        };
        result.putObject("answers").putObject(QUESTION_ID).putArray("answers").add(label);
    }
}
