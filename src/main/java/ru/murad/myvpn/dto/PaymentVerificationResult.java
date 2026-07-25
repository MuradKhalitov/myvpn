package ru.murad.myvpn.dto;

import ru.murad.myvpn.model.PaymentActivationStatus;
import ru.murad.myvpn.model.PaymentStatus;
import java.time.Instant;

public record PaymentVerificationResult(PaymentVerificationOutcome outcome,
        PaymentStatus paymentStatus, PaymentActivationStatus activationStatus,
        Instant paidAt, Instant nextCheckAt) {
    @Override public String toString() {
        return "PaymentVerificationResult[outcome=" + outcome + ", paymentStatus="
                + paymentStatus + ", activationStatus=" + activationStatus
                + ", nextCheckAt=" + nextCheckAt + "]";
    }
}
