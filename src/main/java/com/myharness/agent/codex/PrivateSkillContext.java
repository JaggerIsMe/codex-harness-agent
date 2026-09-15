package com.myharness.agent.codex;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.Set;

/** Trusted text loading; the sandbox never receives filesystem access to the package. */
final class PrivateSkillContext {
    private static final int MAX_BYTES=512*1024;
    private static final Set<String> TEXT=Set.of("md","txt","json","yaml","yml","toml","csv","tsv","py","js","ts","sh","ps1","sql","xml","html","css","scss");
    private PrivateSkillContext() { }

    static String load(List<CodexSkillInput> skills) {
        StringBuilder result=new StringBuilder("Harness 专家 Skill 是可选能力，仅在适用当前任务时使用。以下正文由可信 Agent 加载。"
                +"私有技能路径不能通过文件工具或命令访问；请使用这里的指令和文本资源。"
                +"包内脚本仅以参考源码提供，不能按私有路径直接执行；需要执行时遵守当前工作区工具权限。\n");
        long remaining=MAX_BYTES;
        try {
            for(var skill:skills) {
                Path root=Path.of(skill.path()).getParent().toRealPath();
                result.append("\n<expert-skill name=\"").append(skill.name()).append("\">\n");
                try(var files=Files.walk(root)) {
                    for(Path file:files.filter(p->!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)).sorted().toList()) {
                        Path relative=root.relativize(file);
                        String name=file.getFileName().toString();
                        if(name.equals(".harness-version") || name.equals(".harness-expert-managed"))continue;
                        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().equals(file))throw new IOException("Private Skill contains a link");
                        String extension=name.substring(name.lastIndexOf('.')+1).toLowerCase(java.util.Locale.ROOT);
                        if(!TEXT.contains(extension)) {
                            result.append("[非文本资源不可直接访问：").append(relative).append("]\n");
                            continue;
                        }
                        long size=Files.size(file);
                        if(size>remaining)throw new IOException("Expert Skill text exceeds the 512 KiB context limit");
                        byte[] bytes;
                        try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {bytes=input.readNBytes((int)remaining+1);}
                        if(bytes.length>remaining)throw new IOException("Expert Skill text exceeds the context limit");
                        remaining-=bytes.length;
                        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                        result.append("\n--- ").append(relative.toString().replace('\\','/')).append(" ---\n").append(text).append('\n');
                    }
                }
                result.append("</expert-skill>\n");
            }
            return result.toString();
        } catch(IOException failure) {throw new CodexException("无法加载私有专家文本资源",failure);}
    }
}
