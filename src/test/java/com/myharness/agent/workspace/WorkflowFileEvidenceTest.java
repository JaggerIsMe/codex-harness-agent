package com.myharness.agent.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.agent.config.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowFileEvidenceTest {
    @TempDir Path temporary;
    @Test void checksUserDotfileBytesAndRejectsEscapesDirectoriesAndHardLinks() throws Exception {
        Path root=Files.createDirectory(temporary.resolve("workspace"));
        var settings=new AgentProperties();settings.setDataDir(temporary.resolve("data"));
        var workspace=new WorkspaceProperties();workspace.setName("demo");workspace.setPath(root);settings.setWorkspaces(List.of(workspace));
        var registry=new WorkspaceRegistry(settings,new ObjectMapper());
        assertEquals("MISSING",WorkflowFileEvidence.fingerprint(registry,"demo","reports/data.xlsx"));
        Files.createDirectory(root.resolve("reports"));Files.writeString(root.resolve("reports/data.xlsx"),"original");
        String before=WorkflowFileEvidence.fingerprint(registry,"demo","reports/data.xlsx");
        assertTrue(before.matches("[0-9a-f]{64}"));
        assertEquals(before,WorkflowFileEvidence.fingerprint(registry,"demo","reports/data.xlsx"));
        Files.writeString(root.resolve("reports/data.xlsx"),"updated");
        assertNotEquals(before,WorkflowFileEvidence.fingerprint(registry,"demo","reports/data.xlsx"));
        Files.createDirectory(root.resolve(".codex"));Files.writeString(root.resolve(".codex/user.json"),"original");
        assertEquals(before,WorkflowFileEvidence.fingerprint(registry,"demo",".codex/user.json"));
        for(String path:List.of("../private","reports"))assertEquals("UNVERIFIED",WorkflowFileEvidence.fingerprint(registry,"demo",path));
        Files.createLink(root.resolve("linked.xlsx"),root.resolve("reports/data.xlsx"));
        assertEquals("UNVERIFIED",WorkflowFileEvidence.fingerprint(registry,"demo","linked.xlsx"));
    }
}
