package com.myharness.agent.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.agent.config.*;
import com.myharness.agent.entity.dto.CreateWorkspaceCommandDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AgentStorageTest {
    @TempDir Path root;

    @Test void newWorkspaceContainsNoPlatformFilesAndPrivateMarkerSurvivesRestart() throws Exception {
        Path parent=Files.createDirectory(root.resolve("projects"));
        var properties=properties();var allowed=new WorkspaceRootProperties();allowed.setName("parent");allowed.setPath(parent);
        properties.setWorkspaceRoots(List.of(allowed));
        var registry=new WorkspaceRegistry(properties,new ObjectMapper());
        var request=new CreateWorkspaceCommandDTO();request.setRequestId("create-1");request.setParentName("parent");request.setWorkspaceName("project");
        assertTrue(registry.create(request).isSuccess());
        Path workspace=registry.resolve("project","");
        try(var entries=Files.list(workspace)){assertEquals(0,entries.count());}
        assertFalse(Files.exists(properties.getDataDir().resolve("workspace-private")));
        var marker=properties.getDataDir().resolve("workspace-creation/"+AgentStorage.key("create-1")+".json");
        assertTrue(Files.readString(marker).contains("directoryIdentity"));
        Files.createDirectories(workspace.resolve(".harness/user-files"));
        Files.writeString(workspace.resolve(".harness/user-files/keep.txt"),"user content");
        Files.writeString(workspace.resolve(".harness-workspace.json"),"user metadata");
        assertTrue(new WorkspaceRegistry(properties,new ObjectMapper()).create(request).isSuccess());
        assertEquals("user content",Files.readString(workspace.resolve(".harness/user-files/keep.txt")));
        assertEquals("user metadata",Files.readString(workspace.resolve(".harness-workspace.json")));
        assertFalse(Files.exists(properties.getDataDir().resolve("workspace-private")));
    }

    @ParameterizedTest
    @ValueSource(strings={"file","user-directory","legacy-shaped-directory","marker-directory"})
    void preservesUserOwnedHarnessNamesOnFirstStartupAndRestart(String shape) throws Exception {
        Path project=Files.createDirectory(root.resolve("project"));
        Path content;
        if(shape.equals("legacy-shaped-directory")) {
            content=Files.createDirectories(project.resolve(".harness/expert-skills/harness-1-2")).resolve("SKILL.md");
            Files.createDirectories(project.resolve(".harness/expert-runtimes"));
            Files.createDirectories(project.resolve(".harness/exec-tmp"));
        } else if(shape.equals("user-directory")) {
            content=Files.createDirectories(project.resolve(".harness/user-files")).resolve("keep.txt");
        } else content=project.resolve(".harness");
        Files.writeString(content,"user content");
        Path marker=shape.equals("marker-directory")
                ? Files.createDirectory(project.resolve(".harness-workspace.json")).resolve("keep.txt")
                : project.resolve(".harness-workspace.json");
        Files.writeString(marker,"user metadata");
        for(String name:List.of(".git",".codex",".agent",".agents")) {
            Files.createDirectory(project.resolve(name));Files.writeString(project.resolve(name+"/user.txt"),"user content");
        }
        var properties=properties();var allowed=new WorkspaceProperties();allowed.setName("project");allowed.setPath(project);properties.setWorkspaces(List.of(allowed));
        for(int start=0;start<2;start++) {
            assertEquals(project.toRealPath(),new WorkspaceRegistry(properties,new ObjectMapper()).resolve("project",""));
            assertEquals("user content",Files.readString(content));
            assertEquals("user metadata",Files.readString(marker));
            for(String name:List.of(".git",".codex",".agent",".agents"))assertEquals("user content",Files.readString(project.resolve(name+"/user.txt")));
            assertFalse(Files.exists(properties.getDataDir().resolve("workspace-private")));
        }
    }

    @Test void isolatesProjectsAndRejectsWorkspaceAccessToPrivatePaths() throws Exception {
        Path a=Files.createDirectory(root.resolve("a")),b=Files.createDirectory(root.resolve("b"));var properties=properties();
        var first=new WorkspaceProperties();first.setName("a");first.setPath(a);var second=new WorkspaceProperties();second.setName("b");second.setPath(b);
        properties.setWorkspaces(List.of(first,second));var registry=new WorkspaceRegistry(properties,new ObjectMapper());
        Path one=registry.privateDirectory("a","expert-skills"),two=registry.privateDirectory("b","expert-skills");
        assertNotEquals(one,two);assertFalse(one.startsWith(a));assertFalse(one.startsWith(b));
        Path execution=AgentStorage.executionDirectory(properties.getDataDir(),a);assertFalse(execution.startsWith(one.getParent()));
        assertThrows(WorkspaceAccessException.class,()->registry.resolve("a",one.toString()));
        assertThrows(java.io.IOException.class,()->new WorkspacePathPolicy(registry).checked("a","../data/workspace-private"));
        assertThrows(WorkspaceAccessException.class,()->registry.privateDirectory("unknown","expert-skills"));
        assertThrows(java.io.IOException.class,()->AgentStorage.directory(properties.getDataDir(),"../escape"));
    }

    private AgentProperties properties(){var p=new AgentProperties();p.setDataDir(root.resolve("data"));return p;}
}
