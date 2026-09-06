package com.myharness.agent.codex;

import java.nio.file.Path;

public class CodexThreadOptions {
    private boolean isolatedExpertRuntime;
    private java.util.List<CodexSkillInput> expertSkills=java.util.List.of();
    private java.util.List<com.myharness.agent.entity.dto.McpRuntimeDTO> mcpServers=java.util.List.of();
    public CodexThreadOptions withExpertSkills(java.util.List<CodexSkillInput> value) {
        isolatedExpertRuntime=true;expertSkills=java.util.List.copyOf(value);return this;
    }
    public CodexThreadOptions withExpertRuntime(java.util.List<CodexSkillInput> skills,java.util.List<com.myharness.agent.entity.dto.McpRuntimeDTO> servers) {
        isolatedExpertRuntime=true;expertSkills=java.util.List.copyOf(skills);mcpServers=java.util.List.copyOf(servers);return this;
    }
    public boolean isIsolatedExpertRuntime() { return isolatedExpertRuntime; }
    public java.util.List<CodexSkillInput> getExpertSkills() { return expertSkills; }
    public java.util.List<com.myharness.agent.entity.dto.McpRuntimeDTO> getMcpServers() { return mcpServers; }
    public String getMcpRuntimeKey() {
        return mcpServers.stream().map(value -> String.valueOf(value.getConfigurationVersionId())+":"+value.getConfigDigest()+":"+value.getServerCode())
                .sorted().collect(java.util.stream.Collectors.joining(","));
    }
    private final String projectId;
    private final Path workspace;
    private final com.myharness.agent.entity.dto.ModelRuntimeDTO modelRuntime;

    public CodexThreadOptions(String projectId, Path workspace, Object modelOrRuntime) {
        this.projectId = projectId;
        this.workspace = workspace;
        if(modelOrRuntime instanceof com.myharness.agent.entity.dto.ModelRuntimeDTO runtime) this.modelRuntime=runtime;
        else {com.myharness.agent.entity.dto.ModelRuntimeDTO runtime=new com.myharness.agent.entity.dto.ModelRuntimeDTO();runtime.setModelId((String)modelOrRuntime);this.modelRuntime=runtime;}
    }

    public String getProjectId() { return projectId; }
    public Path getWorkspace() { return workspace; }
    public String getModel() { return modelRuntime==null?null:modelRuntime.getModelId(); }
    public com.myharness.agent.entity.dto.ModelRuntimeDTO getModelRuntime(){return modelRuntime;}
    public String getModelRuntimeKey(){return modelRuntime==null?null:modelRuntime.getRuntimeKey();}
}
