package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectIsolationCheckTest {
    @TempDir Path data;

    @Test void missingWindowsRuntimeExplainsPreparationBeforeLaunchingCodex() {
        var properties=properties();
        Path python=data.resolve("runtime/python/python.exe");
        properties.setWindowsPython(python);
        properties.setCodexCommand(data.resolve("must-not-launch-codex.exe").toString());
        var failure=assertThrows(CodexException.class,
                ()->new ProjectIsolationCheck(properties,new ObjectMapper()).initializeFor("Windows 10"));
        assertAll(
                ()->assertTrue(failure.getMessage().contains(python.toString()),failure.getMessage()),
                ()->assertTrue(failure.getMessage().contains("prepare-windows-runtime.ps1"),failure.getMessage()),
                ()->assertTrue(failure.getMessage().contains("harness.agent.windows-python"),failure.getMessage()),
                ()->assertEquals("UNSUPPORTED",properties.isolationMode("Windows 10")));
    }

    @Test void advertisesOnlyAfterSuccessfulProbeAndCleansFixtures() throws Exception {
        var properties=properties();
        probe(properties,0,true,false).initializeFor("Linux");
        assertEquals("LINUX_PROJECT_PROFILE_V1",properties.isolationMode("Linux"));
        try(var files=Files.list(data)) {assertEquals(0,files.count());}
    }

    @Test void readLeakExitCannotAdvertiseIsolation() throws Exception {
        var properties=properties();
        assertThrows(CodexException.class,()->probe(properties,21,true,false).initializeFor("Linux"));
        assertEquals("UNSUPPORTED",properties.isolationMode("Linux"));
    }

    @Test void missingSuccessMarkerOrExternalMutationCannotPass() throws Exception {
        assertThrows(CodexException.class,()->probe(properties(),0,false,false).initializeFor("Linux"));
        assertThrows(CodexException.class,()->probe(properties(),0,true,true).initializeFor("Linux"));
    }

    private AgentProperties properties() {var value=new AgentProperties();value.setDataDir(data);return value;}
    private ProjectIsolationCheck probe(AgentProperties properties,int exit,boolean success,boolean tamper) {
        return new ProjectIsolationCheck(properties,new ObjectMapper()) {
            @Override Process launch(List<String> command,Path workspace,Path output) throws java.io.IOException {
                assertTrue(command.contains("permissions.\"harness-isolation-check\".\"filesystem\".\":root\"=\"deny\""));
                Files.writeString(workspace.resolve("created.txt"),"WRITE_OK");
                Files.writeString(output,success?"HARNESS_ISOLATION_OK\n":"sandbox unsupported\n");
                if(tamper) Files.writeString(workspace.getParent().resolve("outside.txt"),"CHANGED");
                var process=mock(Process.class);
                try {when(process.waitFor(anyLong(),eq(TimeUnit.SECONDS))).thenReturn(true);} catch(InterruptedException failure) {throw new AssertionError(failure);}
                when(process.exitValue()).thenReturn(exit);
                return process;
            }
        };
    }
}
