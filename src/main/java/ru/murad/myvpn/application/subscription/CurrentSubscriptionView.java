package ru.murad.myvpn.application.subscription;

import ru.murad.myvpn.dto.VpnTariffDto;
import ru.murad.myvpn.model.SubscriptionStatus;

import java.time.Instant;
import java.util.UUID;

public record CurrentSubscriptionView(
        UUID id,
        VpnTariffDto tariff,
        SubscriptionStatus status,
        Instant startsAt,
        Instant expiresAt
) {
}
