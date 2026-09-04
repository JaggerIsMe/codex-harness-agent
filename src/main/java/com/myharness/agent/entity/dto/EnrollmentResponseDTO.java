package com.myharness.agent.entity.dto;

public class EnrollmentResponseDTO {
    private String status;
    private int code;
    private String info;
    private EnrollmentDataDTO data;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }
    public String getInfo() { return info; }
    public void setInfo(String info) { this.info = info; }
    public EnrollmentDataDTO getData() { return data; }
    public void setData(EnrollmentDataDTO data) { this.data = data; }

    public static class EnrollmentDataDTO {
        private String deviceCode;
        private String deviceToken;

        public String getDeviceCode() { return deviceCode; }
        public void setDeviceCode(String deviceCode) { this.deviceCode = deviceCode; }
        public String getDeviceToken() { return deviceToken; }
        public void setDeviceToken(String deviceToken) { this.deviceToken = deviceToken; }
    }
}
