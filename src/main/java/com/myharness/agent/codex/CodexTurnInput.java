package com.myharness.agent.codex;

public class CodexTurnInput {
    private final String message;
    private final String model;
    private final String reasoningEffort;

    public CodexTurnInput(String message, String model, String reasoningEffort) {
        this.message = message;
        this.model = model;
        this.reasoningEffort = reasoningEffort;
    }

    public String getMessage() { return message; }
    public String getModel() { return model; }
    public String getReasoningEffort() { return reasoningEffort; }
}
