package com.myharness.agent.skill;

import com.myharness.agent.config.AgentProperties;
import com.myharness.agent.entity.vo.DeviceIdentityVO;
import com.myharness.agent.security.DeviceIdentityProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class ServerSkillDownloadAdapter implements SkillDownloadClient {
    private final AgentProperties properties;
    private final DeviceIdentityProvider identityProvider;

    public ServerSkillDownloadAdapter(AgentProperties properties, DeviceIdentityProvider identityProvider) {
        this.properties = properties;
        this.identityProvider = identityProvider;
    }

    @Override
    public void download(String downloadUrl, Path target, long maximumBytes) {
        URI uri = validateUrl(downloadUrl);
        HttpURLConnection connection = null;
        try {
            DeviceIdentityVO identity = identityProvider.get();
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(60_000);
            connection.setRequestProperty("Authorization", "Bearer " + identity.getDeviceToken());
            connection.setRequestProperty("X-Harness-Device-Code", identity.getDeviceCode());
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new SkillException("Skill download failed with HTTP status " + status);
            }
            long declaredLength = connection.getContentLengthLong();
            if (declaredLength > maximumBytes) {
                throw new SkillException("Skill archive exceeds the configured download limit");
            }
            try (InputStream input = connection.getInputStream(); OutputStream output = Files.newOutputStream(target)) {
                byte[] buffer = new byte[8192];
                long total = 0;
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    total += count;
                    if (total > maximumBytes) {
                        throw new SkillException("Skill archive exceeds the configured download limit");
                    }
                    output.write(buffer, 0, count);
                }
            }
        } catch (IOException exception) {
            throw new SkillException("Unable to download Skill archive", exception);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private URI validateUrl(String downloadUrl) {
        if (downloadUrl == null || downloadUrl.trim().isEmpty()) {
            throw new SkillException("Skill download URL must not be blank");
        }
        final URI uri;
        try {
            uri = new URI(downloadUrl.trim()).normalize();
        } catch (URISyntaxException exception) {
            throw new SkillException("Invalid Skill download URL", exception);
        }
        String scheme = uri.getScheme();
        if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) || uri.getHost() == null) {
            throw new SkillException("Skill download URL must use HTTP or HTTPS");
        }
        URI enrollment = properties.getEnrollmentUrl();
        if (enrollment == null || enrollment.getHost() == null
                || !enrollment.getHost().equalsIgnoreCase(uri.getHost())
                || normalizedPort(enrollment) != normalizedPort(uri)) {
            throw new SkillException("Skill download URL must use the configured Harness Server origin");
        }
        if ("http".equalsIgnoreCase(scheme) && !isLoopback(uri.getHost())) {
            throw new SkillException("Plain HTTP Skill downloads are only allowed from loopback hosts");
        }
        return uri;
    }

    private int normalizedPort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }
}
