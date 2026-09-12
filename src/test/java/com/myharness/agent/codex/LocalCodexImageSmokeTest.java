package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.entity.dto.ModelRuntimeDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in, billable validation using the user's already-authenticated Local Codex. */
@EnabledIfSystemProperty(named="codex.images.smoke",matches="true")
class LocalCodexImageSmokeTest {
    @Test void localCodexGeneratesOneImageThroughControlledTool() throws Exception {
        Path root=Files.createTempDirectory(Path.of("target").toAbsolutePath(),"local-image-smoke-").toRealPath();
        Path project=Files.createDirectory(root.resolve("project"));Path python=Path.of(System.getProperty("windows.isolation.python"));
        var properties=new AgentProperties();properties.setDataDir(Files.createDirectory(root.resolve("data")));properties.setWindowsPython(python);
        properties.setResponsesHistoryCompatibility(true);properties.setCodexRequestTimeoutSeconds(30);
        var runtime=new ModelRuntimeDTO();runtime.setSchemaVersion(2);runtime.setRuntimeMode("LOCAL_CODEX");runtime.setRuntimeKey("local-image-validation");
        try(var adapter=new AppServerCodexAdapter(properties,new ObjectMapper())) {
            String thread=adapter.startThread(new CodexThreadOptions("image-smoke",project,runtime).withExpertRuntime(List.of(),List.of()));
            var completion=new CompletableFuture<String>();var failure=new java.util.concurrent.atomic.AtomicReference<String>();
            adapter.startTurn(thread,new CodexTurnInput("Run exactly one validation call to harness_generate_image. Prompt: a simple blue circle on a plain white background. output_path: validation.png. Do not use other tools or inspect files. Do not retry. After the tool finishes, state success or failure briefly."),new CodexEventListener() {
                public void onEvent(CodexEvent event){if(event.getDetails()!=null&&event.getDetails().has("success")&&!event.getDetails().path("success").asBoolean())failure.set(event.getDetails().path("message").asText());}
                public void onApproval(CodexApproval approval){completion.completeExceptionally(new AssertionError("Unexpected approval"));}
                public void onCompleted(String id,String status,String reason){completion.complete(status);}
            });
            assertEquals("completed",completion.get(7,TimeUnit.MINUTES));
            assertTrue(Files.isRegularFile(project.resolve("validation.png")),"Image not produced: "+failure.get());
            assertNotNull(javax.imageio.ImageIO.read(project.resolve("validation.png").toFile()));
            // Keep only the generated image as a reviewable artifact; fixture state is cleaned below.
            Files.copy(project.resolve("validation.png"),Path.of("target/local-codex-image-validation.png"),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }finally {
            WindowsIsolatedCommand.cleanupProfile(project,python);
            assertTrue(root.startsWith(Path.of("target").toRealPath()));
            try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
    }
}
