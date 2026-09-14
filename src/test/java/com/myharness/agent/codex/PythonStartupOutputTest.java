package com.myharness.agent.codex;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PythonStartupOutputTest {
    private final Path runtime=Path.of("runtime","python.exe");
    private String warning(){return "Failed to find real location of "+runtime;}
    @Test void removesOnlyLeadingKnownRuntimeWarningsFromSuccessfulOutput() {
        String body="report written\r\nUserWarning: keep this\n";
        var result=WindowsIsolatedCommand.normalizePythonStartup(new WindowsIsolatedCommand.Result(0,warning()+"\n"+warning()+"\r\n"+body),runtime);
        assertEquals(0,result.exitCode());assertEquals(body,result.output());
    }
    @Test void preservesFailuresAndUnrelatedOrLaterDiagnostics() {
        for(int code:new int[]{1,125,126}) {
            var failed=new WindowsIsolatedCommand.Result(code,warning()+"\nTraceback: failed\n");
            assertEquals(failed,WindowsIsolatedCommand.normalizePythonStartup(failed,runtime));
        }
        for(String output:new String[]{"Failed to find real location of other/python.exe\n",warning()+" extra text\n","user output\n"+warning()+"\n",warning(),""})
            assertEquals(output,WindowsIsolatedCommand.normalizePythonStartup(new WindowsIsolatedCommand.Result(0,output),runtime).output());
    }
}
