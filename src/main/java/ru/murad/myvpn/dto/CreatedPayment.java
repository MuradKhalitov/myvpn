package ru.murad.myvpn.dto;

import ru.murad.myvpn.model.ProviderPaymentStatus;

import java.net.URI;
import java.time.Instant;

public record CreatedPayment(
        String providerPaymentId,
        ProviderPaymentStatus status,
        URI confirmationUrl,
        Instant providerCreatedAt,
        Instant expiresAt
) {
    @Override
    public String toString() {
        return "CreatedPayment[status=" + status
                + ", providerCreatedAt=" + providerCreatedAt
                + ", expiresAt=" + expiresAt + "]";
    }
}
