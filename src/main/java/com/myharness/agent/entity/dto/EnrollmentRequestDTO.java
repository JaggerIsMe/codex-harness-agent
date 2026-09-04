package com.myharness.agent.entity.dto;

public class EnrollmentRequestDTO {
    private String enrollmentCode;
    private String deviceName;
    private String agentVersion;
    private String osName;
    private String osVersion;

    public String getEnrollmentCode() { return enrollmentCode; }
    public void setEnrollmentCode(String enrollmentCode) { this.enrollmentCode = enrollmentCode; }
    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
    public String getAgentVersion() { return agentVersion; }
    public void setAgentVersion(String agentVersion) { this.agentVersion = agentVersion; }
    public String getOsName() { return osName; }
    public void setOsName(String osName) { this.osName = osName; }
    public String getOsVersion() { return osVersion; }
    public void setOsVersion(String osVersion) { this.osVersion = osVersion; }
}
