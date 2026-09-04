package com.myharness.agent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.entity.vo.DeviceIdentityVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeviceIdentityProviderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void shouldUseConfiguredIdentityWithoutEnrollment() {
        AgentProperties properties = new AgentProperties();
        properties.setDataDir(temporaryDirectory);
        properties.setDeviceCode(" device-1 ");
        properties.setDeviceToken(" token-1 ");

        DeviceIdentityVO identity = new DeviceIdentityProvider(properties, new ObjectMapper()).get();

        assertEquals("device-1", identity.getDeviceCode());
        assertEquals("token-1", identity.getDeviceToken());
    }

    @Test
    void shouldFailWhenNoIdentityOrEnrollmentCodeExists() {
        AgentProperties properties = new AgentProperties();
        properties.setDataDir(temporaryDirectory);

        assertThrows(DeviceIdentityException.class,
                () -> new DeviceIdentityProvider(properties, new ObjectMapper()).get());
    }
}
