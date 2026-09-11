package com.myharness.agent.skill;

import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.workspace.WorkspaceRegistry;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class ExpertSkillCache {
    private static final String VERSION_MARKER = ".harness-version";
    private static final int UNIX_FILE_TYPE_MASK = 0170000;
    private static final int UNIX_REGULAR_FILE = 0100000;
    private static final int UNIX_DIRECTORY = 0040000;

    private final AgentProperties properties;
    private final SkillDownloadClient downloadClient;
    private final WorkspaceRegistry workspaceRegistry;
    private final Path temporaryDirectory;

    public ExpertSkillCache(AgentProperties properties, SkillDownloadClient downloadClient,
                                    WorkspaceRegistry workspaceRegistry) {
        this.properties = properties;
        this.downloadClient = downloadClient;
        this.workspaceRegistry = workspaceRegistry;
        Path dataDirectory = properties.getDataDir().toAbsolutePath().normalize();
        this.temporaryDirectory = dataDirectory.resolve("tmp");
        try {
            Files.createDirectories(temporaryDirectory);
        } catch (IOException exception) {
            throw new SkillException("Unable to prepare Skill directories", exception);
        }
    }

    public synchronized Path prepare(ExpertSkillCacheRequest command, com.myharness.agent.attachment.AttachmentPreparation preparation) {
        if(preparation!=null) preparation.check();
        validateInstall(command);
        String skillId = safeSegment(command.skillId(), "skillId");
        String directoryName = safeSegment("harness-" + skillId, "managedSkillDirectory");
        String version = safeSegment(command.version(), "version");
        Path scopeRoot = scopeRoot(command.workspaceName());
        Path finalDirectory = scopeRoot.resolve(directoryName).normalize();
        ensureInside(scopeRoot, finalDirectory);
        if (Files.isDirectory(finalDirectory.resolve("SKILL.md"))) {
            throw new SkillException("Installed SKILL.md must be a file");
        }
        if (Files.isRegularFile(finalDirectory.resolve("SKILL.md")) && installedVersion(finalDirectory).equals(version)) {
            return finalDirectory;
        }

        Path operationDirectory = temporaryDirectory.resolve("skill-" + UUID.randomUUID()).normalize();
        Path archive = operationDirectory.resolve("skill.zip");
        Path extracted = operationDirectory.resolve("extracted");
        try {
            Files.createDirectories(extracted);
            downloadClient.download(command.downloadUrl(), archive, megabytes(properties.getSkillMaxDownloadSizeMb()),preparation);
            verifySha256(archive, command.sha256());
            extract(archive, extracted);
            if(preparation!=null) preparation.check();
            Path skillRoot = findSkillRoot(extracted);
            Files.write(skillRoot.resolve(VERSION_MARKER), version.getBytes(StandardCharsets.UTF_8));
            Files.createDirectories(finalDirectory.getParent());
            replaceAtomically(skillRoot, finalDirectory);
            return finalDirectory;
        } catch (IOException exception) {
            throw new SkillException("Unable to install Skill", exception);
        } finally {
            deleteTreeQuietly(operationDirectory);
        }
    }

    private void extract(Path archive, Path extracted) throws IOException {
        long expandedBytes = 0;
        int entryCount = 0;
        Set<String> entries = new HashSet<>();
        try (InputStream raw = Files.newInputStream(archive);
             ZipArchiveInputStream zip = new ZipArchiveInputStream(raw, StandardCharsets.UTF_8.name(), true, true)) {
            ZipArchiveEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name == null || name.trim().isEmpty()) {
                    continue;
                }
                entryCount++;
                if (entryCount > properties.getSkillMaxFileCount()) {
                    throw new SkillException("Skill archive contains too many entries");
                }
                Path target = extracted.resolve(name.replace('\\', '/')).normalize();
                if (!target.startsWith(extracted)) {
                    throw new SkillException("Skill archive contains a path traversal entry");
                }
                String key = extracted.relativize(target).toString().toLowerCase(Locale.ROOT);
                if (!entries.add(key)) {
                    throw new SkillException("Skill archive contains duplicate paths: " + name);
                }
                rejectLinkOrSpecialFile(entry);
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (OutputStream output = Files.newOutputStream(target)) {
                    byte[] buffer = new byte[8192];
                    long fileBytes = 0;
                    int count;
                    while ((count = zip.read(buffer)) >= 0) {
                        fileBytes += count;
                        expandedBytes += count;
                        if (fileBytes > megabytes(properties.getSkillMaxSingleFileSizeMb())) {
                            throw new SkillException("Skill archive contains a file larger than the configured limit");
                        }
                        if (expandedBytes > megabytes(properties.getSkillMaxExpandedSizeMb())) {
                            throw new SkillException("Skill archive expands beyond the configured limit");
                        }
                        output.write(buffer, 0, count);
                    }
                }
            }
        }
    }

    private void rejectLinkOrSpecialFile(ZipArchiveEntry entry) {
        if (entry.isUnixSymlink()) {
            throw new SkillException("Skill archive contains a symbolic link: " + entry.getName());
        }
        int mode = entry.getUnixMode();
        int type = mode & UNIX_FILE_TYPE_MASK;
        if (type != 0 && type != UNIX_REGULAR_FILE && type != UNIX_DIRECTORY) {
            throw new SkillException("Skill archive contains a special file: " + entry.getName());
        }
    }

    private Path findSkillRoot(Path extracted) throws IOException {
        if (Files.isRegularFile(extracted.resolve("SKILL.md"))) {
            return extracted;
        }
        List<Path> children;
        try (Stream<Path> stream = Files.list(extracted)) {
            children = stream.collect(Collectors.toList());
        }
        if (children.size() == 1 && Files.isDirectory(children.get(0))
                && Files.isRegularFile(children.get(0).resolve("SKILL.md"))) {
            return children.get(0);
        }
        throw new SkillException("Skill archive must contain SKILL.md at its root or single top-level directory");
    }

    private void verifySha256(Path archive, String expected) throws IOException {
        if (expected == null || !expected.matches("(?i)[0-9a-f]{64}")) {
            throw new SkillException("Skill SHA-256 must contain 64 hexadecimal characters");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        try (InputStream input = Files.newInputStream(archive)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder actual = new StringBuilder(64);
        for (byte value : digest.digest()) {
            actual.append(String.format("%02x", value & 0xff));
        }
        if (!actual.toString().equalsIgnoreCase(expected)) {
            throw new SkillException("Skill archive SHA-256 does not match");
        }
    }

    private void validateInstall(ExpertSkillCacheRequest command) {
        if (command == null) {
            throw new SkillException("Install Skill command is required");
        }
        safeSegment(command.skillId(), "skillId");
        safeSegment(command.version(), "version");
        if (command.downloadUrl() == null || command.downloadUrl().trim().isEmpty()) {
            throw new SkillException("downloadUrl must not be blank");
        }
        if(command.workspaceName()==null || command.workspaceName().isBlank()) throw new SkillException("workspaceName is required");
    }

    private Path scopeRoot(String workspaceName) {
        try {
            Path requested=workspaceRegistry.resolve(workspaceName,".harness/expert-skills");
            Files.createDirectories(requested);
            return workspaceRegistry.resolve(workspaceName,".harness/expert-skills").toRealPath();
        } catch(IOException | RuntimeException error) {throw new SkillException("Unable to prepare expert Skill cache",error);}
    }

    private String safeSegment(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || ".".equals(value) || "..".equals(value)) {
            throw new SkillException(field + " contains unsupported characters");
        }
        return value;
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private void replaceAtomically(Path source, Path target) throws IOException {
        if (!Files.exists(target)) {
            moveAtomically(source, target);
            return;
        }
        if (!Files.isRegularFile(target.resolve(VERSION_MARKER))) {
            throw new SkillException("Refusing to replace a Skill directory not managed by Harness");
        }
        Path backup = target.resolveSibling(target.getFileName() + ".backup-" + UUID.randomUUID());
        moveAtomically(target, backup);
        try {
            moveAtomically(source, target);
            deleteTreeQuietly(backup);
        } catch (IOException | RuntimeException exception) {
            if (!Files.exists(target) && Files.exists(backup)) moveAtomically(backup, target);
            throw exception;
        }
    }

    private String installedVersion(Path directory) {
        Path marker = directory.resolve(VERSION_MARKER);
        if (!Files.isRegularFile(marker)) return "";
        try { return new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trim(); }
        catch (IOException exception) { throw new SkillException("Unable to read installed Skill version", exception); }
    }

    private void ensureInside(Path root, Path target) {
        if (!target.startsWith(root) || target.equals(root)) {
            throw new SkillException("Skill path escapes the managed Skill directory");
        }
    }

    private long megabytes(long value) {
        try {
            return Math.multiplyExact(value, 1024L * 1024L);
        } catch (ArithmeticException exception) {
            throw new SkillException("Configured Skill size limit is too large", exception);
        }
    }

    private void deleteTreeQuietly(Path root) {
        try {
            deleteTree(root);
        } catch (IOException ignored) {
            // Temporary cleanup failure must not hide the installation result.
        }
    }

    private void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
