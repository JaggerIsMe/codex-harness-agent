package com.myharness.agent.config;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentPropertiesTest {
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
