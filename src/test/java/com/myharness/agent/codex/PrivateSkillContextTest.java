package com.myharness.agent.codex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PrivateSkillContextTest {
    @TempDir Path root;
    @Test void loadsInstructionsAndTextResourcesWithoutExposingHostPaths() throws Exception {
        Files.writeString(root.resolve("SKILL.md"),"Use references/rules.md and scripts/check.py");
        Files.createDirectory(root.resolve("references"));Files.writeString(root.resolve("references/rules.md"),"expert rules");
        Files.createDirectory(root.resolve("scripts"));Files.writeString(root.resolve("scripts/check.py"),"print('check')");
        Files.write(root.resolve("asset.png"),new byte[]{0,1,2});
        String context=PrivateSkillContext.load(List.of(new CodexSkillInput("review",root.resolve("SKILL.md").toString())));
        assertTrue(context.contains("expert rules"));assertTrue(context.contains("print('check')"));assertTrue(context.contains("非文本资源不可直接访问"));
        assertFalse(context.contains(root.toString()));
    }
    @Test void failsBeforeModelTurnInsteadOfSilentlyTruncatingOversizedOrInvalidText() throws Exception {
        Path skill=root.resolve("SKILL.md");Files.writeString(skill,"a".repeat(512*1024+1));
        assertThrows(CodexException.class,()->PrivateSkillContext.load(List.of(new CodexSkillInput("review",skill.toString()))));
        Files.write(skill,new byte[]{(byte)0xff});
        assertThrows(CodexException.class,()->PrivateSkillContext.load(List.of(new CodexSkillInput("review",skill.toString()))));
    }
}
