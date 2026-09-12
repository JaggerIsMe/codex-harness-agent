package com.myharness.agent.codex;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WindowsExecutionToolsTest {
    @Test void eventSummaryDoesNotSendImageBytesToPlatformOrMutateModelOutput() {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var item=json.createObjectNode().put("type","dynamicToolCall").put("tool","harness_view_image");
        var content=item.putArray("contentItems");content.addObject().put("type","inputImage").put("imageUrl","data:image/png;base64,PRIVATE_PIXELS");
        content.addObject().put("type","inputText").put("text","Workspace image: demo.png");
        var summary=WindowsExecutionTools.eventSummary(item,json);
        assertFalse(summary.toString().contains("PRIVATE_PIXELS"));assertTrue(item.toString().contains("PRIVATE_PIXELS"));
        assertTrue(summary.path("message").asText().contains("demo.png"));
    }
    @Test void controlledToolsDependOnCapabilitiesWhileNativeSwitchesRemainOff() {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        for(boolean vision:new boolean[]{false,true})for(boolean images:new boolean[]{false,true}) {
            var params=json.createObjectNode();WindowsExecutionTools.configure(params,true,vision,images);
            var names=new java.util.HashSet<String>();params.path("dynamicTools").forEach(tool->names.add(tool.path("name").asText()));
            assertEquals(vision,names.contains("harness_view_image"));assertEquals(images,names.contains("harness_generate_image"));
            assertTrue(names.containsAll(java.util.List.of("harness_execute","harness_apply_patch")));
            for(String flag:WindowsExecutionTools.DISABLED)assertFalse(params.path("config").path("features").path(flag).asBoolean(true));
        }
    }
    @Test void rejectsHostMcpEndpointsBeforeToolActivation() {
        for(String url:new String[]{"http://127.0.0.1:3000/mcp","http://localhost/mcp","http://[::1]/mcp"})
            assertThrows(CodexException.class,()->WindowsExecutionTools.validateRemoteMcp(url));
    }
}
