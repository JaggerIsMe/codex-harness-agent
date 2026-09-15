package com.myharness.agent.config;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPropertiesTest {
    @Test void publicNetworkHasDistinctVerifiedWindowsCapability() {
        var properties=new AgentProperties();
        properties.setCommandNetworkMode(AgentProperties.CommandNetworkMode.PUBLIC);
        assertThat(properties.isolationMode("Windows 10")).isEqualTo("UNSUPPORTED");
        properties.confirmReadIsolation();
        assertThat(properties.isolationMode("Windows 10")).isEqualTo("WINDOWS_LPAC_API_V3");
        assertThat(properties.isolationMode("Linux")).isEqualTo("UNSUPPORTED");
        properties.setCommandNetworkMode(AgentProperties.CommandNetworkMode.DISABLED);
        assertThat(properties.isolationMode("Windows 10")).isEqualTo("WINDOWS_LPAC_SKILL_V2");
    }
    @Test
    void jakartaValidationStillEnforcesIsolationAndNestedWorkspaceConstraints() {
        AgentProperties properties = new AgentProperties();
        properties.setServerUrl(URI.create("ws://127.0.0.1/ws/agent"));
        properties.setDataDir(Path.of("target", "agent-validation").toAbsolutePath());
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(properties)).isEmpty();

            properties.setStrictProjectIsolation(false);
            assertThat(validator.validate(properties)).anySatisfy(violation ->
                    assertThat(violation.getPropertyPath().toString()).isEqualTo("strictProjectIsolation"));

            properties.setStrictProjectIsolation(true);
            properties.setWorkspaces(List.of(new WorkspaceProperties()));
            assertThat(validator.validate(properties)).anySatisfy(violation ->
                    assertThat(violation.getPropertyPath().toString()).startsWith("workspaces[0]."));
        }
    }
}
