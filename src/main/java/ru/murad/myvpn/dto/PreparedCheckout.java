package ru.murad.myvpn.dto;

import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.model.PaymentStatus;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public record PreparedCheckout(
        UUID orderId,
        UUID userId,
        UUID tariffId,
        PaymentProviderType provider,
        UUID idempotenceKey,
        BigDecimal amount,
        String currency,
        String tariffCode,
        String tariffName,
        int durationDays,
        PaymentStatus status,
        URI confirmationUrl,
        Instant localExpiresAt
) {
    @Override
    public String toString() {
        return "PreparedCheckout[provider=" + provider
                + ", amount=" + amount + ", currency=" + currency
                + ", status=" + status
                + ", localExpiresAt=" + localExpiresAt + "]";
    }
}
