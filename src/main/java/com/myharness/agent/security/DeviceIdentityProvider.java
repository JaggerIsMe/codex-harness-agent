package com.myharness.agent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.entity.dto.EnrollmentRequestDTO;
import com.myharness.agent.entity.dto.EnrollmentResponseDTO;
import com.myharness.agent.entity.vo.DeviceIdentityVO;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

@Component
public class DeviceIdentityProvider {
    private static final String IDENTITY_FILE_NAME = "device-identity.json";

    private final AgentProperties properties;
    private final ObjectMapper objectMapper;
    private volatile DeviceIdentityVO cachedIdentity;

    public DeviceIdentityProvider(AgentProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public DeviceIdentityVO get() {
        DeviceIdentityVO identity = cachedIdentity;
        if (identity != null) {
            return identity;
        }
        synchronized (this) {
            if (cachedIdentity == null) {
                cachedIdentity = resolveIdentity();
            }
            return cachedIdentity;
        }
    }

    private DeviceIdentityVO resolveIdentity() {
        if (hasText(properties.getDeviceCode()) && hasText(properties.getDeviceToken())) {
            return new DeviceIdentityVO(properties.getDeviceCode().trim(), properties.getDeviceToken().trim());
        }

        Path identityFile = identityFile();
        if (Files.isRegularFile(identityFile)) {
            try {
                DeviceIdentityVO identity = objectMapper.readValue(identityFile.toFile(), DeviceIdentityVO.class);
                validate(identity);
                return identity;
            } catch (IOException exception) {
                throw new DeviceIdentityException("Unable to read persisted device identity", exception);
            }
        }

        if (!hasText(properties.getEnrollmentCode())) {
            throw new DeviceIdentityException(
                    "Device identity is unavailable; configure device-code/device-token or enrollment-code");
        }
        DeviceIdentityVO enrolled = enroll();
        persist(identityFile, enrolled);
        return enrolled;
    }

    private DeviceIdentityVO enroll() {
        if (properties.getEnrollmentUrl() == null) {
            throw new DeviceIdentityException("Enrollment URL must be configured for first-time enrollment");
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) properties.getEnrollmentUrl().toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(30_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");

            EnrollmentRequestDTO request = new EnrollmentRequestDTO();
            request.setEnrollmentCode(properties.getEnrollmentCode().trim());
            request.setDeviceName(defaultDeviceName());
            request.setAgentVersion(AgentMetadata.version());
            request.setOsName(System.getProperty("os.name"));
            request.setOsVersion(System.getProperty("os.version"));
            try (OutputStream output = connection.getOutputStream()) {
                objectMapper.writeValue(output, request);
            }

            int status = connection.getResponseCode();
            InputStream body = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            if (body == null) {
                throw new DeviceIdentityException("Enrollment failed with HTTP status " + status);
            }
            try (InputStream input = body) {
                EnrollmentResponseDTO response = objectMapper.readValue(input, EnrollmentResponseDTO.class);
                if (status < 200 || status >= 300 || response.getData() == null) {
                    throw new DeviceIdentityException("Enrollment failed: " + safeInfo(response.getInfo(), status));
                }
                DeviceIdentityVO identity = new DeviceIdentityVO(
                        response.getData().getDeviceCode(), response.getData().getDeviceToken());
                validate(identity);
                return identity;
            }
        } catch (IOException exception) {
            throw new DeviceIdentityException("Unable to enroll device", exception);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void persist(Path identityFile, DeviceIdentityVO identity) {
        Path temporary = identityFile.resolveSibling(identityFile.getFileName() + ".tmp");
        try {
            Files.createDirectories(identityFile.getParent());
            try (OutputStream output = Files.newOutputStream(temporary)) {
                objectMapper.writeValue(output, identity);
            }
            restrictPermissions(temporary);
            try {
                Files.move(temporary, identityFile, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, identityFile, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictPermissions(identityFile);
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Preserve the original failure.
            }
            throw new DeviceIdentityException("Unable to persist device identity", exception);
        }
    }

    private Path identityFile() {
        if (properties.getDataDir() == null) {
            throw new DeviceIdentityException("Agent data directory must be configured");
        }
        return properties.getDataDir().toAbsolutePath().normalize().resolve(IDENTITY_FILE_NAME);
    }

    private String defaultDeviceName() {
        return hasText(properties.getDeviceName()) ? properties.getDeviceName().trim() : "unnamed-device";
    }

    private void validate(DeviceIdentityVO identity) {
        if (identity == null || !hasText(identity.getDeviceCode()) || !hasText(identity.getDeviceToken())) {
            throw new DeviceIdentityException("Device identity response is incomplete");
        }
    }

    private void restrictPermissions(Path path) {
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows ACLs are inherited from the configured data directory.
        }
    }

    private String safeInfo(String info, int status) {
        return hasText(info) ? info : "HTTP " + status;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
