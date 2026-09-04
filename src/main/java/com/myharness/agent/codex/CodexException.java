package com.myharness.agent.codex;

public class CodexException extends RuntimeException {
    public CodexException(String message) {
        super(message);
    }

    public CodexException(String message, Throwable cause) {
        super(message, cause);
    }
}
