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

    @Override
    public String toString() {
        return "SubscriptionDto[id=" + id
                + ", telegramId=" + telegramId
                + ", status=" + status
                + ", configurationData=redacted]";
    }
}
