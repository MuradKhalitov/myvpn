package ru.murad.myvpn.dto;

import ru.murad.myvpn.model.SubscriptionStatus;

import java.time.Instant;
import java.util.UUID;

public record SubscriptionDto(
        UUID id,
        long telegramId,
        VpnTariffDto tariff,
        SubscriptionStatus status,
        Instant startsAt,
        Instant expiresAt,
        String providerName,
        String configurationData
) {
}
