package ru.murad.myvpn.service.impl;

import org.junit.jupiter.api.Test;
import ru.murad.myvpn.config.YooKassaProperties;
import ru.murad.myvpn.dto.PreparedPaymentVerification;
import ru.murad.myvpn.dto.ProviderPayment;
import ru.murad.myvpn.exception.ProviderPaymentValidationException;
import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.model.PaymentStatus;
import ru.murad.myvpn.model.ProviderPaymentStatus;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YooKassaPaymentVerificationPolicyTest {
    private final YooKassaPaymentVerificationPolicy policy = new YooKassaPaymentVerificationPolicy(properties());
    private final PreparedPaymentVerification expected = new PreparedPaymentVerification(UUID.randomUUID(), UUID.randomUUID(),
            PaymentProviderType.YOOKASSA, "payment-1", new BigDecimal("100.00"), "RUB", UUID.randomUUID(),
            "MONTH_1", "Month", 30, PaymentStatus.PENDING, UUID.randomUUID());

    @Test
    void rejectsRecipientAccountIdMismatch() {
        assertThatThrownBy(() -> policy.validate(expected, payment("another-shop"), Instant.now()))
                .isInstanceOf(ProviderPaymentValidationException.class)
                .extracting("safeFailureCode").isEqualTo("PAYMENT_RECIPIENT_MISMATCH");
    }

    @Test
    void acceptsConfiguredRecipientAccountId() {
        assertThatCode(() -> policy.validate(expected, payment("shop-123"), Instant.now()))
                .doesNotThrowAnyException();
    }

    private ProviderPayment payment(String recipient) {
        return new ProviderPayment("payment-1", ProviderPaymentStatus.PENDING, false, new BigDecimal("100.00"),
                "RUB", "bank_card", expected.paymentOrderId(), recipient, Instant.parse("2026-09-01T00:00:00Z"), null);
    }

    private YooKassaProperties properties() {
        return new YooKassaProperties(URI.create("https://api.yookassa.test/v3"), "shop-123", "secret",
                URI.create("https://payments.example.test/yookassa/return"), false, Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMinutes(1));
    }
}
