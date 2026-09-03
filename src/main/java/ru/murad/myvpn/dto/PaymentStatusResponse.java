package ru.murad.myvpn.dto;

import ru.murad.myvpn.model.PaymentActivationStatus;
import ru.murad.myvpn.model.PaymentStatus;

import java.time.Instant;
import java.util.UUID;

public record PaymentStatusResponse(
        UUID paymentOrderId,
        PaymentVerificationOutcome outcome,
        PaymentStatus paymentStatus,
        PaymentActivationStatus activationStatus,
        Instant paidAt,
        Instant nextCheckAt,
        Instant expiresAt
) {
}
