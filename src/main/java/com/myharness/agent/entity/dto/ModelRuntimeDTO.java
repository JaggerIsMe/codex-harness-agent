package com.myharness.agent.entity.dto;

import java.util.List;

public class ModelRuntimeDTO {
    private int schemaVersion=1; private Long configurationId; private Long configurationVersionId; private Long versionNo;
    private String runtimeMode; private String configurationCode; private String name; private String providerName; private String baseUrl;
    private String modelId; private List<String> inputModalities=List.of("TEXT"); private int contextWindowTokens=128000; private String configDigest; private String runtimeKey; private String apiKey;
    public int getSchemaVersion(){return schemaVersion;} public void setSchemaVersion(int v){schemaVersion=v;}
    public String getRuntimeMode(){return runtimeMode;} public void setRuntimeMode(String v){runtimeMode=v;}
    public Long getConfigurationId(){return configurationId;} public void setConfigurationId(Long v){configurationId=v;}
    public Long getConfigurationVersionId(){return configurationVersionId;} public void setConfigurationVersionId(Long v){configurationVersionId=v;}
    public Long getVersionNo(){return versionNo;} public void setVersionNo(Long v){versionNo=v;}
    public String getConfigurationCode(){return configurationCode;} public void setConfigurationCode(String v){configurationCode=v;}
    public String getName(){return name;} public void setName(String v){name=v;}
    public String getProviderName(){return providerName;} public void setProviderName(String v){providerName=v;}
    public String getBaseUrl(){return baseUrl;} public void setBaseUrl(String v){baseUrl=v;}
    public String getModelId(){return modelId;} public void setModelId(String v){modelId=v;}
    public List<String> getInputModalities(){return inputModalities==null?List.of():inputModalities;} public void setInputModalities(List<String> v){inputModalities=v;}
    public int getContextWindowTokens(){return contextWindowTokens;} public void setContextWindowTokens(int v){contextWindowTokens=v;}
    public String getConfigDigest(){return configDigest;} public void setConfigDigest(String v){configDigest=v;}
    public String getRuntimeKey(){return runtimeKey;} public void setRuntimeKey(String v){runtimeKey=v;}
    public String getApiKey(){return apiKey;} public void setApiKey(String v){apiKey=v;}
    public boolean supports(String modality){return getInputModalities().contains(modality);}
}
