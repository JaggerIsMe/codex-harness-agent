package com.myharness.agent.codex;

import com.myharness.agent.workspace.AgentStorage;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;

/** Trusted, immutable command permissions. Never constructed from a model's tool arguments. */
record SkillExecutionScope(String key, Path temporaryDirectory, List<Path> readableSkills) {
    SkillExecutionScope {
        readableSkills = List.copyOf(readableSkills);
    }

    static SkillExecutionScope prepare(Path data, CodexThreadOptions options) {
        try {
            Path workspace = options.getWorkspace().toRealPath();
            Path allowed = AgentStorage.directory(AgentStorage.workspaceRoot(data, workspace), "expert-runtimes");
            var directories = new java.util.LinkedHashSet<Path>();
            Path discoveryRoot = null;
            for (CodexSkillInput skill : options.getExpertSkills()) {
                Path entry = Path.of(skill.path()).toAbsolutePath().normalize();
                if (!entry.equals(entry.toRealPath()) || !entry.startsWith(allowed)
                        || !entry.getFileName().toString().equals("SKILL.md"))
                    throw new IOException("Skill must be an unredirected runtime SKILL.md");
                Path relative = allowed.relativize(entry);
                if (relative.getNameCount() != 5 || !relative.getName(2).toString().equals("skills"))
                    throw new IOException("Invalid conversation Skill layout");
                if (options.getConversationId() != null && (!relative.getName(0).toString().equals(options.getConversationId())
                        || !relative.getName(1).toString().equals(options.getExpertRuntimeKey())))
                    throw new IOException("Skill does not belong to this conversation runtime");
                Path parent = entry.getParent().getParent();
                if (discoveryRoot != null && !discoveryRoot.equals(parent))
                    throw new IOException("Cannot combine conversation Skill roots");
                discoveryRoot = parent;
                validateTree(entry.getParent());
                directories.add(entry.getParent());
            }
            String identity = workspace + "\n" + options.getConversationId() + "\n" + options.getExpertRuntimeKey()
                    + "\n" + directories;
            String key = AgentStorage.key(identity);
            // A sibling of the old project execution directory: no inherited project SID grant.
            Path temporary = AgentStorage.directory(data, "skill-execution/" + key);
            return new SkillExecutionScope(key, temporary, List.copyOf(directories));
        } catch (IOException failure) {
            throw new CodexException("无法准备会话 Skill 读取权限", failure);
        }
    }

    static void validateTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                if (!path.equals(path.toRealPath()) || Files.isSymbolicLink(path)
                        || (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)))
                    throw new IOException("Skill contains a link or special file");
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.getFileStore(path).supportsFileAttributeView("unix")
                        && ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).intValue() != 1)
                    throw new IOException("Skill contains a shared hard link");
            }
        }
    }
}
