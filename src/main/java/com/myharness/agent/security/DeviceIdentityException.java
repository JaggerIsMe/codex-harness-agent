package com.myharness.agent.security;

public class DeviceIdentityException extends RuntimeException {
    public DeviceIdentityException(String message) {
        super(message);
    }

    public DeviceIdentityException(String message, Throwable cause) {
        super(message, cause);
    }
}
