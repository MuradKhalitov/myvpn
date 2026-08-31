package ru.murad.myvpn.client;

import java.time.Instant;
import java.util.UUID;

public record VpnProvisionRequest(
        UUID subscriptionId,
        long telegramId,
        Instant expiresAt,
        String stableExternalAccessId,
        String providerClientKey
) {

    public VpnProvisionRequest(UUID subscriptionId, long telegramId, Instant expiresAt) {
        this(subscriptionId, telegramId, expiresAt, null, null);
    }

    public VpnProvisionRequest(UUID subscriptionId, long telegramId, Instant expiresAt,
            String stableExternalAccessId) { this(subscriptionId, telegramId, expiresAt, stableExternalAccessId, null); }

    @Override
    public String toString() {
        return "VpnProvisionRequest[redacted]";
    }
}
