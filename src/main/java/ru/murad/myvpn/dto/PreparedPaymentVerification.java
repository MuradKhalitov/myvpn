package ru.murad.myvpn.dto;

import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.model.PaymentStatus;
import java.math.BigDecimal;
import java.util.UUID;

public record PreparedPaymentVerification(UUID paymentOrderId, UUID accountId,
        PaymentProviderType provider, String providerPaymentId, BigDecimal amount,
        String currency, java.util.UUID tariffId, String tariffCodeSnapshot, String tariffNameSnapshot, int durationDaysSnapshot,
        PaymentStatus currentPaymentStatus, UUID idempotenceKey) {
    @Override public String toString() {
        return "PreparedPaymentVerification[provider=" + provider + ", status="
                + currentPaymentStatus + ", amount=" + amount + ", currency=" + currency + "]";
    }
}
