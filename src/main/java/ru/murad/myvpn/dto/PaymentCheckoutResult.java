package ru.murad.myvpn.dto;

import ru.murad.myvpn.model.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentCheckoutResult(
        UUID paymentOrderId,
        String tariffName,
        BigDecimal amount,
        String currency,
        int durationDays,
        PaymentStatus status,
        CheckoutDestination destination,
        Instant expiresAt
) {
    public PaymentCheckoutResult(
            UUID paymentOrderId, String tariffName, BigDecimal amount,
            String currency, int durationDays, PaymentStatus status,
            java.net.URI confirmationUrl, Instant expiresAt
    ) {
        this(paymentOrderId, tariffName, amount, currency, durationDays, status,
                new CheckoutDestination.RedirectUrl(confirmationUrl), expiresAt);
    }

    public java.net.URI confirmationUrl() {
        return ((CheckoutDestination.RedirectUrl) destination).url();
    }

    @Override
    public String toString() {
        return "PaymentCheckoutResult[tariffName=" + tariffName
                + ", amount=" + amount + ", currency=" + currency
                + ", durationDays=" + durationDays
                + ", status=" + status + ", destination=" + destination.getClass().getSimpleName()
                + ", expiresAt=" + expiresAt + "]";
    }
}
