package ru.murad.myvpn.client;

import java.time.Instant;
import java.util.UUID;

public record VpnProvisionRequest(
        UUID subscriptionId,
        long telegramId,
        Instant expiresAt
) {

    @Override
    public String toString() {
        return "VpnProvisionRequest[redacted]";
    }
}
