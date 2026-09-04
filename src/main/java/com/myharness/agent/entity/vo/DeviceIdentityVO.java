package com.myharness.agent.entity.vo;

public class DeviceIdentityVO {
    private String deviceCode;
    private String deviceToken;

    public DeviceIdentityVO() {
    }

    public DeviceIdentityVO(String deviceCode, String deviceToken) {
        this.deviceCode = deviceCode;
        this.deviceToken = deviceToken;
    }

    public String getDeviceCode() { return deviceCode; }
    public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
    public String getDeviceToken() { return deviceToken; }
    public void setDeviceToken(String deviceToken) { this.deviceToken = deviceToken; }
}
