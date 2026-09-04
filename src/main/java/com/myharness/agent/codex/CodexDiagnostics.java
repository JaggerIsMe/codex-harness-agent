package com.myharness.agent.codex;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Pattern;

/** Bounded, redacted stderr retained only for the lifetime of one App Server process. */
final class CodexDiagnostics {
    private static final int MAX_CHARACTERS = 4096;
    private static final int MAX_LINES = 20;
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");
    private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)[^\\s\"',;]+");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)((?:[\\w.-]*(?:api[_-]?key|token|secret|password|authorization)[\\w.-]*)[\"']?\\s*[:=]\\s*)"
                    + "(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)");
    private static final Pattern OPENAI_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]+");

    private final Deque<String> lines = new ArrayDeque<>();
    private int characters;

    synchronized void add(String line) {
        String safe = redact(line).trim();
        if (safe.isEmpty()) return;
        if (safe.length() > MAX_CHARACTERS) safe = safe.substring(0, MAX_CHARACTERS);
        lines.addLast(safe);
        characters += safe.length();
        while (lines.size() > MAX_LINES || characters > MAX_CHARACTERS) {
            characters -= lines.removeFirst().length();
        }
    }

    synchronized String summary() {
        return lines.isEmpty() ? "" : "; stderr: " + String.join(" | ", lines);
    }

    static String redact(String value) {
        if (value == null) return "Unknown error";
        String safe = ANSI.matcher(value).replaceAll("");
        safe = BEARER.matcher(safe).replaceAll("$1<REDACTED>");
        safe = SECRET.matcher(safe).replaceAll("$1<REDACTED>");
        return OPENAI_KEY.matcher(safe).replaceAll("<REDACTED>");
    }
}
