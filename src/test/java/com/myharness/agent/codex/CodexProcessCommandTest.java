package com.myharness.agent.codex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodexProcessCommandTest {
    @TempDir
    Path directory;

    @Test
    void usesCommandShellForWindowsNpmWrapper() throws Exception {
        Path wrapper = Files.createFile(directory.resolve("codex.cmd"));

        List<String> command = CodexProcessCommand.appServer("codex", "Windows 11",
                directory.toString(), "C:\\Windows\\System32\\cmd.exe");

        assertEquals("C:\\Windows\\System32\\cmd.exe", command.get(0));
        assertEquals("/d", command.get(1));
        assertEquals("/s", command.get(2));
        assertEquals("/c", command.get(3));
        assertTrue(command.get(4).contains('"' + wrapper.toAbsolutePath().toString() + '"'));
        assertTrue(command.get(4).endsWith(" app-server --stdio"));
    }

    @Test
    void keepsExecutableAndArgumentsSeparateOutsideWindows() {
        List<String> command = CodexProcessCommand.appServer("/usr/local/bin/codex", "Linux", null, null);

        assertEquals(3, command.size());
        assertEquals("/usr/local/bin/codex", command.get(0));
        assertEquals("app-server", command.get(1));
        assertEquals("--stdio", command.get(2));
    }

    @Test
    void forcesElevatedSandboxForStrictWindowsIsolation() throws Exception {
        Path wrapper = Files.createFile(directory.resolve("codex.cmd"));
        List<String> command = CodexProcessCommand.appServer("codex",true,"elevated","Windows 10",
                directory.toString(),"cmd.exe");
        assertTrue(command.get(4).contains("--strict-config"));
        assertTrue(command.get(4).contains("windows.sandbox=\"elevated\""));
        assertTrue(command.get(4).startsWith('"' + wrapper.toAbsolutePath().toString() + '"'));
    }

    @Test
    void startsStrictLinuxWithoutWindowsOptions() {
        assertEquals(List.of("codex","app-server","--strict-config","--stdio"),
                CodexProcessCommand.appServer("codex",true,"elevated","Linux",null,null));
        assertThrows(CodexException.class,() -> CodexProcessCommand.appServer("codex",true,"elevated","Mac OS X",null,null));
    }

    @Test void advertisesSupportedPlatformsOnlyAfterVerification() {
        var properties=new com.myharness.agent.config.AgentProperties();
        assertEquals("UNSUPPORTED",properties.isolationMode("Linux"));
        properties.confirmReadIsolation();
        assertEquals("LINUX_PROJECT_PROFILE_V1",properties.isolationMode("Linux"));
        assertEquals("WINDOWS_LPAC_V1",properties.isolationMode("Windows 11"));
        properties.setStrictProjectIsolation(false);
        assertEquals("UNSUPPORTED",properties.isolationMode("Linux"));
    }
}
