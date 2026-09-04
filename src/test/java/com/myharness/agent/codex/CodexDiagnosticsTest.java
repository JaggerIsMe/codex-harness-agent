package com.myharness.agent.codex;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexDiagnosticsTest {
    @Test
    void redactsCredentialsInPlainTextAndJson() {
        String safe = CodexDiagnostics.redact("api_key=plain-secret password='two words' "
                + "{\"access_token\":\"json-secret\"} Authorization: Bearer bearer-secret sk-example-key");
        for (String secret : new String[]{"plain-secret", "two words", "json-secret", "bearer-secret", "sk-example-key"}) {
            assertFalse(safe.contains(secret), safe);
        }
        assertTrue(safe.contains("<REDACTED>"));
    }

    @Test
    void keepsOnlyBoundedRecentDiagnostics() {
        CodexDiagnostics diagnostics = new CodexDiagnostics();
        diagnostics.add("discarded diagnostic");
        for (int i = 0; i < 30; i++) diagnostics.add("recent error " + i);
        assertFalse(diagnostics.summary().contains("discarded diagnostic"));
        assertTrue(diagnostics.summary().contains("recent error 29"));
        diagnostics.add(String.join("", java.util.Collections.nCopies(5000, "x")));
        assertTrue(diagnostics.summary().length() <= 4106);
    }
}
