package ru.murad.myvpn.client;

import java.time.Instant;
import java.util.UUID;

public record VpnProvisionRequest(
        UUID subscriptionId,
        long legacyOwnerId,
        Instant expiresAt,
        String stableExternalAccessId,
        String providerClientKey
) {

    public VpnProvisionRequest(UUID subscriptionId, long legacyOwnerId, Instant expiresAt) {
        this(subscriptionId, legacyOwnerId, expiresAt, null, null);
    }

    public VpnProvisionRequest(UUID subscriptionId, long legacyOwnerId, Instant expiresAt,
            String stableExternalAccessId) { this(subscriptionId, legacyOwnerId, expiresAt, stableExternalAccessId, null); }

    @Override
    public String toString() {
        return "VpnProvisionRequest[redacted]";
    }
}
