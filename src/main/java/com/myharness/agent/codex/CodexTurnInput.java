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
    private java.util.List<String> localImages=java.util.List.of();
    public CodexTurnInput(String message){this.message=message;}
    public CodexTurnInput(String message,String ignoredModel,String ignoredReasoningEffort){this(message);}

    public String getMessage() { return message; }
    public CodexTurnInput withLocalImages(java.util.List<String> value){localImages=java.util.List.copyOf(value);return this;}
    public java.util.List<String> getLocalImages(){return localImages;}
}
