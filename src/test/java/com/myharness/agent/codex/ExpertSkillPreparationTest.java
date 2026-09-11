package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.agent.attachment.AttachmentPreparation;
import com.myharness.agent.config.*;
import com.myharness.agent.entity.dto.*;
import com.myharness.agent.skill.*;
import com.myharness.agent.workspace.WorkspaceRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class ExpertSkillPreparationTest {
    @TempDir Path root;
    Path workspace;
    AgentProperties properties;
    ExpertSkillPreparation preparation;
    StartTurnCommandDTO turn;
    @BeforeEach void setup() throws Exception {
        workspace=Files.createDirectory(root.resolve("project"));
        Path zip=root.resolve("skill.zip");
        try(var out=new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("SKILL.md"));
            out.write("---\nname: harness-native-probe\ndescription: Use when greeted.\n---\nReply HELLO_FROM_NATIVE_SKILL.\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));out.closeEntry();
            out.putNextEntry(new ZipEntry("references/example.txt"));out.write("resource".getBytes());out.closeEntry();
        }
        properties=new AgentProperties();properties.setDataDir(root.resolve("data"));
        var allowed=new WorkspaceProperties();allowed.setName("project");allowed.setPath(workspace);properties.setWorkspaces(List.of(allowed));
        var registry=new WorkspaceRegistry(properties,new ObjectMapper());
        var installer=new ExpertSkillCache(properties,(url,target,max)->{try{Files.copy(zip,target);}catch(Exception e){throw new SkillException("fixture",e);}},registry);
        preparation=new ExpertSkillPreparation(installer,registry);
        turn=new StartTurnCommandDTO();turn.setTurnId("1");turn.setConversationId("1");turn.setWorkspaceName("project");turn.setProjectId("project");
        var runtime=new ExpertRuntimeDTO();runtime.setProjectRevision(1L);runtime.setRuntimeKey("a".repeat(64));runtime.setExpertId(1L);runtime.setExpertVersionId(1L);runtime.setSystemPrompt("Use Skills");
        var skill=new ExpertRuntimeSkillDTO();skill.setSkillId(1L);skill.setVersionId(1L);skill.setName("harness-native-probe");skill.setVersion("1");skill.setDownloadUrl("http://fixture/skill");skill.setSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(zip))));
        runtime.setSkills(List.of(skill));turn.setExpertRuntime(runtime);
    }
    @Test void activatesCompletePackageOutsideSharedDiscoveryDirectory() throws Exception {
        var ready=preparation.prepare(turn,new AttachmentPreparation());
        assertTrue(Path.of(ready.getFirst().path()).startsWith(workspace.resolve(".harness/expert-runtimes/1")));
        assertEquals("resource",Files.readString(Path.of(ready.getFirst().path()).getParent().resolve("references/example.txt")));
        assertFalse(Files.exists(workspace.resolve(".agents/skills/harness-expert-1-1")));
    }
    @Test void conversationsHaveDifferentImmutableRootsAndShareOnlyCache() {
        var first=preparation.prepare(turn,new AttachmentPreparation());
        turn.setConversationId("2");turn.setTurnId("2");turn.getExpertRuntime().setRuntimeKey("b".repeat(64));
        var second=preparation.prepare(turn,new AttachmentPreparation());
        assertNotEquals(first,second);
        assertTrue(Files.exists(Path.of(first.getFirst().path())));
        assertTrue(Files.exists(Path.of(second.getFirst().path())));
        assertTrue(Files.exists(workspace.resolve(".harness/expert-skills/harness-1-1/SKILL.md")));
    }
    @Test void switchingAndClearingDoNotDeleteAnotherRunningConversationsFiles() {
        var first=preparation.prepare(turn,new AttachmentPreparation());
        turn.setConversationId("2");turn.getExpertRuntime().setRuntimeKey("b".repeat(64));
        turn.getExpertRuntime().getSkills().getFirst().setVersionId(2L);
        var second=preparation.prepare(turn,new AttachmentPreparation());
        turn.setConversationId("1");turn.getExpertRuntime().setRuntimeKey("c".repeat(64));turn.getExpertRuntime().setSkills(List.of());
        assertTrue(preparation.prepare(turn,new AttachmentPreparation()).isEmpty());
        assertTrue(Files.exists(Path.of(first.getFirst().path())));
        assertTrue(Files.exists(Path.of(second.getFirst().path())));
    }
    @Test void rejectsLegacyProtocolAndInvalidConversationIdentifiers() {
        turn.getExpertRuntime().setSchemaVersion(1);
        assertThrows(com.myharness.agent.command.AgentOperationException.class,()->preparation.prepare(turn,new AttachmentPreparation()));
        turn.getExpertRuntime().setSchemaVersion(2);turn.setConversationId("../other");
        assertThrows(com.myharness.agent.command.AgentOperationException.class,()->preparation.prepare(turn,new AttachmentPreparation()));
    }
    @Test void refusesChangingSkillsWithinAnExistingRuntimeKey() {
        preparation.prepare(turn,new AttachmentPreparation());turn.getExpertRuntime().setSkills(List.of());
        assertThrows(CodexException.class,()->preparation.prepare(turn,new AttachmentPreparation()));
    }
    @Test @EnabledIfSystemProperty(named="codex.skills.probe",matches="true")
    void installedCodexKeepsTwoConversationSkillCatalogsSeparateWithoutStartingModels() throws Exception {
        var first=preparation.prepare(turn,new AttachmentPreparation());
        turn.setConversationId("2");turn.getExpertRuntime().setRuntimeKey("b".repeat(64));
        var second=preparation.prepare(turn,new AttachmentPreparation());
        properties.setStrictProjectIsolation(false);properties.setCodexRequestTimeoutSeconds(20);
        var json=new ObjectMapper();
        // Only skills/extraRoots/set and skills/list: no thread, model or Windows sandbox setup.
        try(var one=new AppServerCodexAdapter(properties,json);var two=new AppServerCodexAdapter(properties,json)) {
            one.configureSkillRoots(workspace,first);two.configureSkillRoots(workspace,second);
            assertEquals(List.of(Path.of(first.getFirst().path()).toRealPath()),discovered(one));
            assertEquals(List.of(Path.of(second.getFirst().path()).toRealPath()),discovered(two));
            one.configureSkillRoots(workspace,List.of());
            assertTrue(discovered(one).isEmpty());
            assertEquals(List.of(Path.of(second.getFirst().path()).toRealPath()),discovered(two));
        }
    }
    private List<Path> discovered(AppServerCodexAdapter codex) throws Exception {
        var params=new ObjectMapper().createObjectNode();params.putArray("cwds").add(workspace.toString());params.put("forceReload",true);
        var found=codex.request("skills/list",params);List<Path> paths=new ArrayList<>();
        for(var group:found.path("data")) for(var skill:group.path("skills")) {
            if("harness-native-probe".equals(skill.path("name").asText()) && skill.path("enabled").asBoolean())
                paths.add(Path.of(skill.path("path").asText()).toRealPath());
        }
        return paths;
    }
}
