package ru.murad.myvpn.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

@ConfigurationProperties(prefix = "telegram")
public record TelegramProperties(Set<Long> adminIds, String botToken) {

    public TelegramProperties {
        adminIds = adminIds == null ? Set.of() : Set.copyOf(adminIds);
        botToken = botToken == null ? "" : botToken;
    }
}
