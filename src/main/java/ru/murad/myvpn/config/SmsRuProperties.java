package ru.murad.myvpn.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "sms.ru")
public record SmsRuProperties(boolean enabled, String apiId, URI baseUrl,
                              Duration connectTimeout, Duration readTimeout) {
    @Override public String toString() {
        return "SmsRuProperties[enabled=" + enabled + ", apiId=<redacted>, baseUrl=" + baseUrl + "]";
    }
}
