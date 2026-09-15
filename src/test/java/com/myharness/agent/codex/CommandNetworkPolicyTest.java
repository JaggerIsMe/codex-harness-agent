package com.myharness.agent.codex;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.agent.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class CommandNetworkPolicyTest {
    @TempDir Path root;

    @Test void toolDescriptionsMatchSelectedModeWithoutChangingNativeToolPermissions() {
        var json=new ObjectMapper();var properties=new AgentProperties();
        properties.setCommandNetworkMode(AgentProperties.CommandNetworkMode.PUBLIC);
        var params=json.createObjectNode();WindowsExecutionTools.configure(params,true);
        WindowsExecutionTools.configureCommands(params,properties);WindowsExecutionTools.configureNetwork(params,properties);
        for(var tool:params.path("dynamicTools")) {
            if(!tool.path("name").asText().matches("harness_execute|harness_run_command"))continue;
            String description=tool.path("description").asText();
            assertTrue(description.contains("Network mode: PUBLIC"));
            assertFalse(description.contains("command networking are inaccessible"));
            assertFalse(description.contains("or access the network"));
        }
        assertFalse(params.path("config").path("features").path("unified_exec").asBoolean());
        assertFalse(ProjectPermissionProfile.policy(json).path("network").path("enabled").asBoolean());
    }

    @Test void changingNetworkModeRequiresNewThreadButSameModeCanResume() throws Exception {
        var properties=new AgentProperties();properties.setDataDir(Files.createDirectory(root.resolve("data")));
        Path workspace=Files.createDirectory(root.resolve("workspace"));
        var adapter=new AppServerCodexAdapter(properties,new ObjectMapper());
        ReflectionTestUtils.invokeMethod(adapter,"markNativeThread","thread",workspace,true);
        properties.setCommandNetworkMode(AgentProperties.CommandNetworkMode.PUBLIC);
        var failure=assertThrows(CodexException.class,()->ReflectionTestUtils.invokeMethod(adapter,"markNativeThread","thread",workspace,false));
        assertTrue(failure.getMessage().contains("请新建会话"));
        ReflectionTestUtils.invokeMethod(adapter,"markNativeThread","new-thread",workspace,true);
        assertDoesNotThrow(()->ReflectionTestUtils.invokeMethod(adapter,"markNativeThread","new-thread",workspace,false));
        properties.setCommandNetworkMode(AgentProperties.CommandNetworkMode.DISABLED);
        assertThrows(CodexException.class,()->ReflectionTestUtils.invokeMethod(adapter,"markNativeThread","new-thread",workspace,false));
    }
}
