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
        Duration connectTimeout,
        Duration readTimeout,
        int maxMutationAttempts,
        int maxRequestsPerOperation,
        Duration retryInitialDelay,
        Duration retryMaxDelay
) {
}
