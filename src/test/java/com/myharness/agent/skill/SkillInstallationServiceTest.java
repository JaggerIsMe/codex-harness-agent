package com.myharness.agent.skill;

import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.entity.dto.InstallSkillCommandDTO;
import com.myharness.agent.entity.dto.RemoveSkillCommandDTO;
import com.myharness.agent.entity.dto.SkillResultEventDTO;
import com.myharness.agent.workspace.WorkspaceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillInstallationServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldInstallAndRemoveVerifiedSkill() throws Exception {
        Path archive = zip("demo/SKILL.md", "# Demo\n", "demo/scripts/run.txt", "safe");
        SkillInstallationService service = service(archive);
        InstallSkillCommandDTO install = install("skill-1", "1.0.0", archive);

        SkillResultEventDTO installed = service.install(install);
        Path installedDirectory = java.nio.file.Paths.get(installed.getInstalledPath());

        assertTrue(installed.isSuccess());
        assertTrue(Files.isRegularFile(installedDirectory.resolve("SKILL.md")));
        assertEquals("safe", new String(Files.readAllBytes(installedDirectory.resolve("scripts/run.txt")),
                StandardCharsets.UTF_8));

        RemoveSkillCommandDTO remove = new RemoveSkillCommandDTO();
        remove.setSkillId("skill-1");
        remove.setVersion("1.0.0");
        service.remove(remove);
        assertFalse(Files.exists(installedDirectory));
    }

    @Test
    void shouldRejectPathTraversalAndChecksumMismatch() throws Exception {
        Path traversal = zip("../outside.txt", "escape", "SKILL.md", "# Demo");
        SkillInstallationService traversalService = service(traversal);
        InstallSkillCommandDTO traversalCommand = install("skill-1", "1.0.0", traversal);

        assertThrows(SkillException.class, () -> traversalService.install(traversalCommand));
        assertFalse(Files.exists(temporaryDirectory.resolve("outside.txt")));

        Path valid = zip("SKILL.md", "# Demo", "asset.txt", "ok");
        SkillInstallationService checksumService = service(valid);
        InstallSkillCommandDTO checksumCommand = install("skill-2", "1.0.0", valid);
        checksumCommand.setSha256(new String(new char[64]).replace('\0', '0'));

        assertThrows(SkillException.class, () -> checksumService.install(checksumCommand));
    }

    @Test
    void shouldReplaceManagedCodexSkillWithNewVersion() throws Exception {
        Path first = zip("SKILL.md", "# V1", "asset.txt", "one");
        Path second = zip("SKILL.md", "# V2", "asset.txt", "two");
        Path installDirectory = temporaryDirectory.resolve("codex-skills");
        AgentProperties properties = properties(first);
        properties.setSkillInstallDir(installDirectory);
        final Path[] source = { first };
        SkillInstallationService service = new SkillInstallationService(properties, (url, target, maximum) -> {
            try { Files.copy(source[0], target); } catch (IOException exception) { throw new SkillException("copy failed", exception); }
        }, mock(WorkspaceRegistry.class));
        InstallSkillCommandDTO command = install("skill-1", "1.0.0", first);
        command.setSkillName("demo");
        service.install(command);

        source[0] = second;
        command.setVersion("2.0.0"); command.setSha256(sha256(second));
        Path installed = java.nio.file.Paths.get(service.install(command).getInstalledPath());

        assertEquals("two", new String(Files.readAllBytes(installed.resolve("asset.txt")), StandardCharsets.UTF_8));
        assertEquals("2.0.0", new String(Files.readAllBytes(installed.resolve(".harness-version")), StandardCharsets.UTF_8));
        try (Stream<Path> installedSkills = Files.list(installDirectory)) {
            assertEquals(1L, installedSkills.count());
        }
    }

    @Test
    void shouldInstallProjectSkillUnderAuthorizedWorkspace() throws Exception {
        Path archive = zip("SKILL.md", "# Project", "asset.txt", "scoped");
        AgentProperties properties = properties(archive);
        Path workspace = temporaryDirectory.resolve("project-workspace"); Files.createDirectories(workspace);
        WorkspaceRegistry registry = mock(WorkspaceRegistry.class);
        when(registry.resolve("project-a", ".agents/skills")).thenReturn(workspace.resolve(".agents/skills"));
        SkillInstallationService service = new SkillInstallationService(properties, (url, target, maximum) -> {
            try { Files.copy(archive, target); } catch (IOException exception) { throw new SkillException("copy failed", exception); }
        }, registry);
        InstallSkillCommandDTO command = install("42", "1.0.0", archive);
        command.setSkillName("project-skill"); command.setScopeType("PROJECT"); command.setWorkspaceName("project-a");

        Path installed = java.nio.file.Paths.get(service.install(command).getInstalledPath());

        assertEquals(workspace.resolve(".agents/skills/harness-42").toRealPath(), installed.toRealPath());
        assertTrue(Files.isRegularFile(installed.resolve("SKILL.md")));
    }

    private SkillInstallationService service(Path source) {
        AgentProperties properties = properties(source);
        SkillDownloadClient client = (downloadUrl, target, maximumBytes) -> {
            try {
                Files.copy(source, target);
            } catch (IOException exception) {
                throw new SkillException("copy failed", exception);
            }
        };
        return new SkillInstallationService(properties, client, mock(WorkspaceRegistry.class));
    }

    private AgentProperties properties(Path source) {
        AgentProperties properties = new AgentProperties();
        properties.setDataDir(temporaryDirectory.resolve("agent-data-" + source.getFileName()));
        properties.setSkillMaxDownloadSizeMb(20); properties.setSkillMaxExpandedSizeMb(100);
        properties.setSkillMaxSingleFileSizeMb(20); properties.setSkillMaxFileCount(500);
        return properties;
    }

    private InstallSkillCommandDTO install(String skillId, String version, Path archive) throws Exception {
        InstallSkillCommandDTO command = new InstallSkillCommandDTO();
        command.setSkillId(skillId);
        command.setVersion(version);
        command.setDownloadUrl("http://localhost/skill.zip");
        command.setSha256(sha256(archive));
        return command;
    }

    private Path zip(String firstName, String firstContent, String secondName, String secondContent) throws IOException {
        Path archive = Files.createTempFile(temporaryDirectory, "skill-", ".zip");
        try (OutputStream output = Files.newOutputStream(archive); ZipOutputStream zip = new ZipOutputStream(output)) {
            add(zip, firstName, firstContent);
            add(zip, secondName, secondContent);
        }
        return archive;
    }

    private void add(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private String sha256(Path archive) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(archive)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder value = new StringBuilder();
        for (byte part : digest.digest()) {
            value.append(String.format("%02x", part & 0xff));
        }
        return value.toString();
    }
}
