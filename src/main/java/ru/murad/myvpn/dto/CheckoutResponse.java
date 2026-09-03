package ru.murad.myvpn.dto;

import ru.murad.myvpn.model.PaymentStatus;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public record CheckoutResponse(
        UUID paymentOrderId,
        String tariffName,
        BigDecimal amount,
        String currency,
        int durationDays,
        PaymentStatus status,
        URI confirmationUrl,
        Instant expiresAt
) {
}
