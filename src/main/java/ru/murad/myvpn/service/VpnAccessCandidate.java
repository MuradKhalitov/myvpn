package ru.murad.myvpn.service;

import java.util.UUID;

public record VpnAccessCandidate(
        UUID subscriptionId,
        String externalAccessId
) {
}
