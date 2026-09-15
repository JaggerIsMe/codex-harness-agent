package com.myharness.agent.codex;

import com.myharness.agent.workspace.AgentStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SkillExecutionScopeTest {
    @TempDir Path root;

    @Test void bindsExactConversationVersionAndKeepsTemporaryDirectoriesSeparate() throws Exception {
        Path workspace=Files.createDirectory(root.resolve("project")),data=Files.createDirectory(root.resolve("data"));
        String version="a".repeat(64);
        Path pkg=AgentStorage.directory(AgentStorage.workspaceRoot(data,workspace),"expert-runtimes/38/"+version+"/skills/pkg");
        Path file=Files.writeString(pkg.resolve("SKILL.md"),"PRIVATE_BODY");
        var options=new CodexThreadOptions("6",workspace,null).withExecutionIdentity("38",version)
                .withExpertSkills(List.of(new CodexSkillInput("hello",file.toString())));
        var scope=SkillExecutionScope.prepare(data,options);
        assertEquals(List.of(pkg),scope.readableSkills());
        assertFalse(scope.temporaryDirectory().startsWith(AgentStorage.executionDirectory(data,workspace)));
        var other=new CodexThreadOptions("6",workspace,null).withExecutionIdentity("39",version);
        assertNotEquals(scope.temporaryDirectory(),SkillExecutionScope.prepare(data,other).temporaryDirectory());
        other.withExpertSkills(options.getExpertSkills());
        assertThrows(CodexException.class,()->SkillExecutionScope.prepare(data,other));
        options.withExecutionIdentity("38","b".repeat(64));
        assertThrows(CodexException.class,()->SkillExecutionScope.prepare(data,options));
    }

    @Test void doesNotReadSkillBodyOrDuplicateNativeMetadataInDeveloperInstructions() throws Exception {
        Path file=Files.writeString(root.resolve("SKILL.md"),"BODY_MARKER".repeat(100000));
        Files.writeString(root.resolve("script.py"),"SOURCE_MARKER");
        var skill=new CodexSkillInput("hello",file.toString());
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var params=json.createObjectNode();
        AppServerCodexAdapter.configureExpert(params,new CodexTurnInput("Hello").withExpert("Expert",List.of(skill)),"model",List.of(skill));
        String text=params.path("collaborationMode").path("settings").path("developer_instructions").asText();
        assertFalse(text.contains(file.toString()));
        assertFalse(text.contains("BODY_MARKER"));assertFalse(text.contains("SOURCE_MARKER"));
        assertTrue(text.length()<5000);
    }

    @Test void permissionProfileDoesNotExposeAncestorOrOldExecutionDirectory() throws Exception {
        Path workspace=Files.createDirectory(root.resolve("project")),data=Files.createDirectory(root.resolve("data"));
        Path pkg=AgentStorage.directory(data,"packages/current");
        Path temp=AgentStorage.directory(data,"skill-execution/current");
        var policy=ProjectPermissionProfile.policy(new com.fasterxml.jackson.databind.ObjectMapper(),data,workspace,
                new SkillExecutionScope("current",temp,List.of(pkg))).path("filesystem");
        assertEquals("read",policy.path(pkg.toString()).asText());
        assertEquals("write",policy.path(temp.toString()).asText());
        assertEquals("deny",policy.path(":root").asText());
        assertFalse(policy.has(data.toString()));
        assertFalse(policy.has(AgentStorage.executionDirectory(data,workspace).toString()));
    }
}
