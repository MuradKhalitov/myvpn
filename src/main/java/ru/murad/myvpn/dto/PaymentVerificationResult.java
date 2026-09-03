package ru.murad.myvpn.dto;

import ru.murad.myvpn.model.PaymentActivationStatus;
import ru.murad.myvpn.model.PaymentStatus;
import java.time.Instant;
import java.util.UUID;

public record PaymentVerificationResult(UUID paymentOrderId, PaymentVerificationOutcome outcome,
        PaymentStatus paymentStatus, PaymentActivationStatus activationStatus,
        Instant paidAt, Instant nextCheckAt, Instant expiresAt) {
    public PaymentVerificationResult(PaymentVerificationOutcome outcome,
            PaymentStatus paymentStatus, PaymentActivationStatus activationStatus,
            Instant paidAt, Instant nextCheckAt) {
        this(null, outcome, paymentStatus, activationStatus, paidAt, nextCheckAt, null);
    }

    @Override public String toString() {
        return "PaymentVerificationResult[outcome=" + outcome + ", paymentStatus="
                + paymentStatus + ", activationStatus=" + activationStatus
                + ", nextCheckAt=" + nextCheckAt + "]";
    }
}
