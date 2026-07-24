package ru.murad.myvpn.service;

import java.time.Instant;
import java.util.UUID;

public record ActivationPreparation(
        UUID subscriptionId,
        long userTelegramId,
        Instant expiresAt,
        Type type,
        String existingExternalAccessId
) {
    public enum Type {
        PROVISION,
        EXTEND,
        REPLACE_EXPIRED
    }
}
