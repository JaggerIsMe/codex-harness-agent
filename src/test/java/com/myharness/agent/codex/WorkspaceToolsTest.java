package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class WorkspaceToolsTest {
    @TempDir Path root;
    private Path project() throws Exception {return Files.createDirectory(root.resolve("project"));}
    @Test void patchCreatesEditsMovesDeletesAndPreservesCrlf() throws Exception {
        Path project=project();Files.writeString(project.resolve("a.txt"),"one\r\ntwo\r\n");Files.writeString(project.resolve("delete.txt"),"remove");
        String result=WorkspacePatchTool.apply(project,"""
                *** Begin Patch
                *** Add File: added.txt
                +new
                *** Update File: a.txt
                *** Move to: moved.txt
                @@
                 one
                -two
                +three
                *** Delete File: delete.txt
                *** End Patch
                """);
        assertTrue(result.contains("a.txt -> moved.txt"));assertEquals("new\n",Files.readString(project.resolve("added.txt")));
        assertEquals("one\r\nthree\r\n",Files.readString(project.resolve("moved.txt")));
        assertFalse(Files.exists(project.resolve("a.txt")));assertFalse(Files.exists(project.resolve("delete.txt")));
    }
    @Test void preflightFailureNeverWritesEarlierFiles() throws Exception {
        Path project=project();Files.writeString(project.resolve("a.txt"),"old\n");Files.writeString(project.resolve("b.txt"),"different\n");
        assertThrows(java.io.IOException.class,()->WorkspacePatchTool.apply(project,"""
                *** Begin Patch
                *** Update File: a.txt
                @@
                -old
                +new
                *** Update File: b.txt
                @@
                -missing
                +new
                *** End Patch
                """));assertEquals("old\n",Files.readString(project.resolve("a.txt")));
    }
    @Test void ambiguousContextAndExternalPathsAreRejectedButUserDotfilesAreWritable() throws Exception {
        Path project=project();Files.writeString(project.resolve("a.txt"),"same\nsame\n");
        assertThrows(java.io.IOException.class,()->WorkspacePatchTool.apply(project,"*** Begin Patch\n*** Update File: a.txt\n@@\n-same\n+new\n*** End Patch"));
        Files.createDirectories(project.resolve(".git"));Files.createDirectories(project.resolve("nested/.codex"));
        for(String path:new String[]{".git/config","nested/.codex/config.toml"}) {
            WorkspacePatchTool.apply(project,"*** Begin Patch\n*** Add File: "+path+"\n+x\n*** End Patch");
            assertEquals("x\n",Files.readString(project.resolve(path)));
        }
        for(String path:new String[]{"../outside.txt","C:/outside.txt","a.txt:stream","CON.txt","a.txt ","\\\\server\\file"})
            assertThrows(java.io.IOException.class,()->WorkspacePatchTool.apply(project,"*** Begin Patch\n*** Add File: "+path+"\n+x\n*** End Patch"),path);
        assertEquals("same\nsame\n",Files.readString(project.resolve("a.txt")));
    }
    @Test void imageReturnsRealPixelsAndRejectsDisguisedText() throws Exception {
        Path project=project();ImageIO.write(new BufferedImage(3,2,BufferedImage.TYPE_INT_RGB),"png",project.resolve("image.png").toFile());
        var result=WorkspaceImageTool.view(project,"image.png",new ObjectMapper());
        String url=result.path("contentItems").get(1).path("imageUrl").asText();
        assertTrue(url.startsWith("data:image/png;base64,"));assertTrue(result.path("success").asBoolean());
        var decoded=ImageIO.read(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(url.substring(url.indexOf(',')+1))));
        assertEquals(3,decoded.getWidth());assertEquals(2,decoded.getHeight());
        Files.writeString(project.resolve("fake.png"),"private text");
        assertThrows(java.io.IOException.class,()->WorkspaceImageTool.view(project,"fake.png",new ObjectMapper()));
        assertThrows(java.io.IOException.class,()->WorkspaceImageTool.view(project,"../image.png",new ObjectMapper()));
    }
    @Test void outsideHardlinksCannotBeReadChangedDeletedOrUsedAsMoveDestination() throws Exception {
        Path project=project(),outside=root.resolve("outside.png");ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",outside.toFile());
        byte[] before=Files.readAllBytes(outside);Files.createLink(project.resolve("link.png"),outside);
        assertThrows(java.io.IOException.class,()->WorkspaceImageTool.view(project,"link.png",new ObjectMapper()));
        assertThrows(java.io.IOException.class,()->WorkspacePatchTool.apply(project,"*** Begin Patch\n*** Delete File: link.png\n*** End Patch"));
        Files.writeString(project.resolve("a.txt"),"old\n");
        assertThrows(java.io.IOException.class,()->WorkspacePatchTool.apply(project,"*** Begin Patch\n*** Update File: a.txt\n*** Move to: link.png\n@@\n-old\n+new\n*** End Patch"));
        assertEquals("old\n",Files.readString(project.resolve("a.txt")));assertArrayEquals(before,Files.readAllBytes(outside));
    }
    @Test void junctionCannotExposeExternalImagesOrPatchTargets() throws Exception {
        Path project=project(),outside=Files.createDirectory(root.resolve("outside"));
        ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",outside.resolve("image.png").toFile());
        var process=new ProcessBuilder("cmd.exe","/d","/c","mklink","/J",project.resolve("link").toString(),outside.toString()).redirectErrorStream(true).start();
        assertEquals(0,process.waitFor(),new String(process.getInputStream().readAllBytes()));
        try {
            assertThrows(java.io.IOException.class,()->WorkspaceImageTool.view(project,"link/image.png",new ObjectMapper()));
            assertThrows(java.io.IOException.class,()->WorkspacePatchTool.apply(project,"*** Begin Patch\n*** Add File: link/new.txt\n+x\n*** End Patch"));
            assertFalse(Files.exists(outside.resolve("new.txt")));
        }finally{Files.delete(project.resolve("link"));}
    }
}
