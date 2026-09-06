package com.myharness.agent.codex;

import com.myharness.agent.attachment.AttachmentPreparation;
import com.myharness.agent.workspace.WorkspaceRegistry;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Immutable per-conversation discovery roots; the project cache is never a discovery root. */
final class ExpertSkillActivation {
    private static final String MARKER=".harness-expert-managed";
    private static final String OWNER="harness-project-expert-v1";
    static List<CodexSkillInput> sync(WorkspaceRegistry registry,String workspace,String relativeRoot,List<CodexSkillInput> cached,AttachmentPreparation cancellation) {
        try {
            cleanupLegacy(registry,workspace,cancellation);
            Path root=registry.resolve(workspace,relativeRoot);
            Files.createDirectories(root);root=registry.resolve(workspace,relativeRoot).toRealPath();
            if(!root.equals(registry.resolve(workspace,"").resolve(relativeRoot)))
                throw new CodexException("专家运行目录不能重定向到其他目录");
            Set<String> desired=new HashSet<>();List<CodexSkillInput> active=new ArrayList<>();
            for(var skill:cached) {
                cancellation.check();Path source=Path.of(skill.path()).getParent().toRealPath();
                String name="harness-expert-"+source.getFileName().toString().substring("harness-".length());
                desired.add(name);Path target=registry.resolve(workspace,relativeRoot+"/"+name);
                if(!target.equals(root.resolve(name))) throw new CodexException("专家 Skill 安装目录不能是链接："+name);
                if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)) {
                    if(!owned(target)) throw new CodexException("专家 Skill 安装目录与已有文件冲突："+name);
                } else {
                    Path staging=registry.resolve(workspace,".harness/expert-skills/activation-"+UUID.randomUUID());
                    try {
                        copy(source,staging,cancellation);
                        Files.writeString(staging.resolve(MARKER),OWNER);
                        cancellation.check();
                        try {Files.move(staging,target,StandardCopyOption.ATOMIC_MOVE);}
                        catch(AtomicMoveNotSupportedException ignored){Files.move(staging,target);}
                    } finally {if(Files.exists(staging,LinkOption.NOFOLLOW_LINKS)) delete(staging);}
                }
                active.add(new CodexSkillInput(skill.name(),registry.resolve(workspace,relativeRoot+"/"+name+"/SKILL.md").toString()));
            }
            cancellation.check();
            try(var children=Files.list(root)) {
                for(Path child:children.toList()) {
                    String name=child.getFileName().toString();
                    if(name.startsWith("harness-expert-") && !desired.contains(name)) {
                        throw new CodexException("不可变专家运行目录包含清单外的 Skill，请检查运行配置标识");
                    }
                }
            }
            return List.copyOf(active);
        } catch(IOException failure) {throw new CodexException("无法准备会话独立的专家 Skills",failure);}
    }
    private static void cleanupLegacy(WorkspaceRegistry registry,String workspace,AttachmentPreparation cancellation) throws IOException {
        Path root=registry.resolve(workspace,".agents/skills");
        if(!Files.isDirectory(root)) return;
        if(!root.equals(registry.resolve(workspace,"").resolve(".agents/skills")))
            throw new CodexException("共享技能目录不能重定向到其他目录");
        try(var children=Files.list(root)) {
            for(Path child:children.toList()) {
                cancellation.check();String name=child.getFileName().toString();
                if(!name.startsWith("harness-expert-")) continue;
                Path checked=registry.resolve(workspace,".agents/skills/"+name);
                if(checked.equals(child) && owned(checked)) delete(checked);
            }
        }
    }
    private static boolean owned(Path directory) throws IOException {
        Path marker=directory.resolve(MARKER);
        return Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS) && Files.size(marker)==OWNER.length() && OWNER.equals(Files.readString(marker));
    }
    private static void copy(Path source,Path target,AttachmentPreparation cancellation) throws IOException {
        Files.walkFileTree(source,new SimpleFileVisitor<>() {
            private void check(Path path) throws IOException {
                cancellation.check();
                if(Files.isSymbolicLink(path) || !path.toRealPath().startsWith(source)) throw new IOException("Skill package contains a link outside its root");
            }
            @Override public FileVisitResult preVisitDirectory(Path path,BasicFileAttributes attrs) throws IOException {
                check(path);Files.createDirectories(target.resolve(source.relativize(path)));return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path,BasicFileAttributes attrs) throws IOException {
                check(path);if(!attrs.isRegularFile()) throw new IOException("Skill package contains a special file");
                Files.copy(path,target.resolve(source.relativize(path)));return FileVisitResult.CONTINUE;
            }
        });
    }
    private static void delete(Path root) throws IOException {
        Files.walkFileTree(root,new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs) throws IOException {Files.delete(file);return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult postVisitDirectory(Path dir,IOException failure) throws IOException {if(failure!=null)throw failure;Files.delete(dir);return FileVisitResult.CONTINUE;}
        });
    }
}
