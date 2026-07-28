package ru.murad.myvpn.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "payment.yookassa")
public record YooKassaProperties(
        URI baseUrl,
        String shopId,
        String secretKey,
        URI returnUrl,
        boolean webhookEnabled,
        Duration connectTimeout,
        Duration readTimeout,
        Duration retryAfterMin,
        Duration retryAfterMax
) {
    @Override
    public String toString() {
        return "YooKassaProperties[configured=" + configured() + ", secretKeyRedacted=true]";
    }

    private boolean configured() {
        return baseUrl != null && shopId != null && !shopId.isBlank()
                && secretKey != null && !secretKey.isBlank() && returnUrl != null;
    }
}
