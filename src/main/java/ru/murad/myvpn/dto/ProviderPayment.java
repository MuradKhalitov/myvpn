package ru.murad.myvpn.dto;

import ru.murad.myvpn.model.ProviderPaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProviderPayment(
        String providerPaymentId,
        ProviderPaymentStatus status,
        boolean paid,
        BigDecimal amount,
        String currency,
        String paymentMethodType,
        UUID paymentOrderIdFromMetadata,
        String recipientAccountId,
        Instant createdAt,
        Instant paidAt
) {
    @Override
    public String toString() {
        return "ProviderPayment[status=" + status + ", paid=" + paid
                + ", amount=" + amount + ", currency=" + currency
                + ", paymentMethodType=" + paymentMethodType
                + ", createdAt=" + createdAt + ", paidAt=" + paidAt + "]";
    }
}
