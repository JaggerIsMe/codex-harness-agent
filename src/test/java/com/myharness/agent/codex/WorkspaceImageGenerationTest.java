package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class WorkspaceImageGenerationTest {
    @TempDir Path root;
    private final ObjectMapper json=new ObjectMapper();
    private byte[] png() throws Exception {var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,3,BufferedImage.TYPE_INT_RGB),"png",out);return out.toByteArray();}
    @Test void generationAndEditingUseVerifiedPixelsAndSaveNewFilesOnly() throws Exception {
        byte[] pixels=png();Files.write(root.resolve("reference.png"),pixels);
        var args=json.createObjectNode().put("prompt","synthetic fixture").put("output_path","generated.png");
        args.putArray("reference_paths").add("reference.png");
        var result=WorkspaceImageGenerationTool.generate(root,args,json,(request,edit)-> {
            assertTrue(edit);assertEquals("gpt-image-2",request.path("model").asText());
            assertTrue(request.path("images").get(0).path("image_url").asText().startsWith("data:image/png;base64,"));
            assertFalse(request.toString().contains(root.toString()));return pixels;
        });assertTrue(result.path("success").asBoolean());assertArrayEquals(pixels,Files.readAllBytes(root.resolve("generated.png")));
        assertThrows(java.io.IOException.class,()->WorkspaceImageGenerationTool.generate(root,args,json,(request,edit)->{fail("Existing file must be rejected before request");return pixels;}));
    }
    @Test void malformedReferencesAndTargetsNeverReachService() throws Exception {
        var calls=new AtomicInteger();byte[] pixels=png();
        for(String path:new String[]{"../outside.png",".codex/image.png","C:/outside.png","image.png:stream","link/image.png"}) {
            var args=json.createObjectNode().put("prompt","fixture").put("output_path",path);
            assertThrows(java.io.IOException.class,()->WorkspaceImageGenerationTool.generate(root,args,json,(request,edit)->{calls.incrementAndGet();return pixels;}));
        }
        var args=json.createObjectNode().put("prompt","fixture").put("output_path","ok.png");args.putArray("reference_paths").add("../outside.png");
        assertThrows(java.io.IOException.class,()->WorkspaceImageGenerationTool.generate(root,args,json,(request,edit)->{calls.incrementAndGet();return pixels;}));
        assertEquals(0,calls.get());
    }
    @Test void outputCreatedDuringRequestIsNotOverwrittenAndBadResponsesAreNotSaved() throws Exception {
        byte[] pixels=png();var args=json.createObjectNode().put("prompt","fixture").put("output_path","race.png");
        assertThrows(java.io.IOException.class,()->WorkspaceImageGenerationTool.generate(root,args,json,(request,edit)->{Files.writeString(root.resolve("race.png"),"concurrent");return pixels;}));
        assertEquals("concurrent",Files.readString(root.resolve("race.png")));
        args.put("output_path","bad.png");assertThrows(java.io.IOException.class,()->WorkspaceImageGenerationTool.generate(root,args,json,(request,edit)->"not an image".getBytes()));
        assertFalse(Files.exists(root.resolve("bad.png")));
    }
    @Test void cancellationAfterResponsePreventsFileCreation() throws Exception {
        byte[] pixels=png();var args=json.createObjectNode().put("prompt","fixture").put("output_path","cancelled.png");
        try {
            assertThrows(java.io.IOException.class,()->WorkspaceImageGenerationTool.generate(root,args,json,(request,edit)->{Thread.currentThread().interrupt();return pixels;}));
            assertFalse(Files.exists(root.resolve("cancelled.png")));
        }finally{Thread.interrupted();}
    }
}
