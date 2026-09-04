package com.myharness.agent.workspace;

public class WorkspaceAccessException extends RuntimeException {

    public WorkspaceAccessException(String message) {
        super(message);
    }

    public WorkspaceAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
