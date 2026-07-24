package ru.murad.myvpn.client;

import java.time.Instant;

public record VpnExtensionRequest(
        String externalAccessId,
        Instant expiresAt
) {
}
