package ru.murad.myvpn.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

@ConfigurationProperties(prefix = "telegram")
public record TelegramProperties(Set<Long> adminIds) {

    public TelegramProperties {
        adminIds = adminIds == null ? Set.of() : Set.copyOf(adminIds);
    }
}
