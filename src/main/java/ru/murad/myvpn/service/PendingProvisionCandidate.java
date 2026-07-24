package ru.murad.myvpn.service;

import java.time.Instant;
import java.util.UUID;

public record PendingProvisionCandidate(
        UUID subscriptionId,
        long userTelegramId,
        Instant expiresAt,
        UUID claimToken,
        int attemptNumber,
        int maximumAttempts
) {
    public boolean isLastAllowedAttempt() {
        return attemptNumber >= maximumAttempts;
    }
}
