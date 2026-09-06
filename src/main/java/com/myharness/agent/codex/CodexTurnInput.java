package com.myharness.agent.codex;

public class CodexTurnInput {
    private boolean managedExpert;
    private String expertInstructions;
    private java.util.List<CodexSkillInput> skills=java.util.List.of();
    public CodexTurnInput withExpert(String instructions, java.util.List<CodexSkillInput> skills) {
        this.managedExpert=true; this.expertInstructions=instructions; this.skills=java.util.List.copyOf(skills); return this;
    }
    public boolean isManagedExpert() {return managedExpert;}
    public String getExpertInstructions() {return expertInstructions;}
    public java.util.List<CodexSkillInput> getSkills() {return skills;}
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
