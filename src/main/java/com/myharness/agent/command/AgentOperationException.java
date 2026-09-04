package com.myharness.agent.command;

public class AgentOperationException extends RuntimeException {
    private final String errorCode;

    public AgentOperationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public AgentOperationException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
