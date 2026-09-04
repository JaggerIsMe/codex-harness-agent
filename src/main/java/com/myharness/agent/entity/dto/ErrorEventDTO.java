package com.myharness.agent.entity.dto;

public class ErrorEventDTO {
    private final String errorCode;
    private final String message;
    private final String commandType;
    private final String commandMessageId;

    public ErrorEventDTO(String errorCode, String message, String commandType, String commandMessageId) {
        this.errorCode = errorCode;
        this.message = message;
        this.commandType = commandType;
        this.commandMessageId = commandMessageId;
    }

    public String getErrorCode() { return errorCode; }
    public String getMessage() { return message; }
    public String getCommandType() { return commandType; }
    public String getCommandMessageId() { return commandMessageId; }
}
