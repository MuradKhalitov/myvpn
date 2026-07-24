package ru.murad.myvpn.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "vpn.three-x-ui")
public record ThreeXUiProperties(
        URI baseUrl,
        String webBasePath,
        String username,
        String password,
        int inboundId,
        String publicHost,
        Integer publicPortOverride,
        Duration connectTimeout,
        Duration readTimeout,
        int maxMutationAttempts,
        int maxRequestsPerOperation,
        Duration retryInitialDelay,
        Duration retryMaxDelay
) {

    @Override
    public String toString() {
        return "ThreeXUiProperties["
                + "configured=" + isConfigured()
                + ", baseUrlRedacted=true"
                + ", webBasePathRedacted=true"
                + ", usernameRedacted=true"
                + ", passwordRedacted=true"
                + ", inboundIdPresent=" + (inboundId > 0)
                + ", publicHostRedacted=true"
                + ", publicPortOverridePresent="
                + (publicPortOverride != null)
                + "]";
    }

    private boolean isConfigured() {
        return baseUrl != null
                && webBasePath != null && !webBasePath.isBlank()
                && username != null && !username.isBlank()
                && password != null && !password.isBlank()
                && inboundId > 0
                && publicHost != null && !publicHost.isBlank();
    }
}
