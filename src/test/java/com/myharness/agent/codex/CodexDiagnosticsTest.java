package com.myharness.agent.codex;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexDiagnosticsTest {
    @Test void redactsEphemeralCompatibilityRoute() {
        for(String scheme:java.util.List.of("http", "ws")) {
            String safe=CodexDiagnostics.redact(scheme+"://127.0.0.1:12345/01234567-89ab-cdef-0123-456789abcdef/responses");
            assertFalse(safe.contains("01234567"));assertTrue(safe.endsWith("/responses"));
            assertTrue(safe.startsWith(scheme+"://"));
        }
    }
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
