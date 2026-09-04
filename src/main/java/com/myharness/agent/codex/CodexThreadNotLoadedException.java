package com.myharness.agent.codex;

/** Only a precise thread/read absence; configuration, access and binding failures must not recreate history. */
public class CodexThreadNotLoadedException extends CodexException {
    public CodexThreadNotLoadedException(String threadId, Throwable cause) {
        super("Codex thread is not loaded or persisted: " + threadId, cause);
    }
}
