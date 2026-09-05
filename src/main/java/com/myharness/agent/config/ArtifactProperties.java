package com.myharness.agent.config;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix="harness.agent.artifacts")
public class ArtifactProperties {
    @Min(1) private long maxFileBytes=20L*1024*1024;
    @Min(1) @Max(100) private int maxFiles=5;
    @Min(1) private long maxTotalBytes=50L*1024*1024;
    @Min(1) private long maxSpoolBytes=500L*1024*1024;
    @Min(1) private int maxSpoolFiles=1000;
    public long getMaxFileBytes(){return maxFileBytes;}
    public void setMaxFileBytes(long v){maxFileBytes=v;}
    public int getMaxFiles(){return maxFiles;}
    public void setMaxFiles(int v){maxFiles=v;}
    public long getMaxTotalBytes(){return maxTotalBytes;}
    public void setMaxTotalBytes(long v){maxTotalBytes=v;}
    public long getMaxSpoolBytes(){return maxSpoolBytes;}
    public void setMaxSpoolBytes(long v){maxSpoolBytes=v;}
    public int getMaxSpoolFiles(){return maxSpoolFiles;}
    public void setMaxSpoolFiles(int v){maxSpoolFiles=v;}
}
