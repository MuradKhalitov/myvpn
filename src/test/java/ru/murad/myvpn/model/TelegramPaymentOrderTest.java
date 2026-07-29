package ru.murad.myvpn.model;

import org.junit.jupiter.api.Test;
import ru.murad.myvpn.exception.PaymentStateTransitionException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramPaymentOrderTest {

    private static final Instant NOW = Instant.parse("2026-07-29T10:00:00Z");

    @Test
    void createsOpaqueStablePayloadAndStoresInvoice() {
        PaymentOrder first = order();
        PaymentOrder second = order();
        assertThat(first.getTelegramInvoicePayload())
                .matches("[A-Za-z0-9_-]{43}")
                .doesNotContain(first.getId().toString())
                .isNotEqualTo(second.getTelegramInvoicePayload());

        first.markCreating(NOW);
        first.markTelegramInvoiceSent(42, NOW.plusSeconds(1));
        first.markTelegramInvoiceSent(42, NOW.plusSeconds(2));

        assertThat(first.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(first.getTelegramInvoiceMessageId()).isEqualTo(42);
        assertThat(first.getProviderPaymentId()).isEqualTo(first.getTelegramInvoicePayload());
    }

    @Test
    void successfulPaymentIsIdempotentButChargeIdsCannotChange() {
        PaymentOrder order = order();
        order.markCreating(NOW);
        order.markTelegramInvoiceSent(42, NOW.plusSeconds(1));
        order.recordSuccessfulTelegramPayment(
                "tg-charge", "provider-charge", NOW.plusSeconds(2), NOW.plusSeconds(2));
        order.recordSuccessfulTelegramPayment(
                "tg-charge", "provider-charge", NOW.plusSeconds(3), NOW.plusSeconds(3));

        assertThat(order.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(order.getTelegramPaymentChargeId()).isEqualTo("tg-charge");
        assertThat(order.getProviderPaymentChargeId()).isEqualTo("provider-charge");
        assertThatThrownBy(() -> order.recordSuccessfulTelegramPayment(
                "different", "provider-charge", NOW.plusSeconds(4), NOW.plusSeconds(4)))
                .isInstanceOf(PaymentStateTransitionException.class);
    }

    private PaymentOrder order() {
        TelegramUser user = TelegramUser.builder().id(UUID.randomUUID())
                .telegramId(100).chatId(200).role(UserRole.USER)
                .createdAt(NOW).updatedAt(NOW).build();
        VpnTariff tariff = VpnTariff.builder().id(UUID.randomUUID())
                .code("MONTH").name("Monthly").durationDays(30)
                .price(new BigDecimal("149.90")).currency("RUB").active(true)
                .createdAt(NOW).updatedAt(NOW).build();
        return PaymentOrder.create(user, tariff,
                PaymentProviderType.TELEGRAM_YOOKASSA, NOW, Duration.ofHours(1));
    }
}
