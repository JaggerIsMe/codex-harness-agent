package com.myharness.agent.entity.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.List;
import java.util.Map;

public class McpRuntimeDTO {
    private Long configurationId;
    private Long configurationVersionId;
    private Long versionNo;
    private String serverCode;
    private String name;
    private String configDigest;
    private String transportType;
    private String command;
    private List<String> args=List.of();
    private String cwdMode;
    private List<String> envVars=List.of();
    private String url;
    private Map<String,String> httpHeaders=Map.of();
    private int startupTimeoutSeconds=10;
    private int toolTimeoutSeconds=60;
    private boolean required=true;
    private List<String> enabledTools=List.of();
    private List<String> disabledTools=List.of();
    public Long getConfigurationId(){return configurationId;} public void setConfigurationId(Long v){configurationId=v;}
    public Long getConfigurationVersionId(){return configurationVersionId;} public void setConfigurationVersionId(Long v){configurationVersionId=v;}
    public Long getVersionNo(){return versionNo;} public void setVersionNo(Long v){versionNo=v;}
    public String getServerCode(){return serverCode;} public void setServerCode(String v){serverCode=v;}
    public String getName(){return name;} public void setName(String v){name=v;}
    public String getConfigDigest(){return configDigest;} public void setConfigDigest(String v){configDigest=v;}
    public String getTransportType(){return transportType;} public void setTransportType(String v){transportType=v;}
    public String getCommand(){return command;} public void setCommand(String v){command=v;}
    public List<String> getArgs(){return args;} public void setArgs(List<String> v){args=v;}
    public String getCwdMode(){return cwdMode;} public void setCwdMode(String v){cwdMode=v;}
    public List<String> getEnvVars(){return envVars;} public void setEnvVars(List<String> v){envVars=v;}
    public String getUrl(){return url;} public void setUrl(String v){url=v;}
    public Map<String,String> getHttpHeaders(){return httpHeaders;} public void setHttpHeaders(Map<String,String> v){httpHeaders=v;}
    public int getStartupTimeoutSeconds(){return startupTimeoutSeconds;} public void setStartupTimeoutSeconds(int v){startupTimeoutSeconds=v;}
    public int getToolTimeoutSeconds(){return toolTimeoutSeconds;} public void setToolTimeoutSeconds(int v){toolTimeoutSeconds=v;}
    public boolean isRequired(){return required;} public void setRequired(boolean v){required=v;}
    public List<String> getEnabledTools(){return enabledTools;} public void setEnabledTools(List<String> v){enabledTools=v;}
    public List<String> getDisabledTools(){return disabledTools;} public void setDisabledTools(List<String> v){disabledTools=v;}
    @JsonAnySetter public void rejectUnknownField(String name,Object value){throw new IllegalArgumentException("不支持的 MCP 运行字段："+name);}
}
