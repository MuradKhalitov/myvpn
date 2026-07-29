package ru.murad.myvpn.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.murad.myvpn.dto.TelegramPreCheckoutCommand;
import ru.murad.myvpn.dto.TelegramSuccessfulPaymentCommand;
import ru.murad.myvpn.exception.PaymentProviderUncertainException;
import ru.murad.myvpn.model.*;
import ru.murad.myvpn.repository.PaymentOrderRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TelegramPaymentTransactionServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-07-29T10:00:00Z");
    private final PaymentOrderRepository orders = mock(PaymentOrderRepository.class);
    private final TelegramPaymentTransactionServiceImpl service =
            new TelegramPaymentTransactionServiceImpl(
                    orders, Clock.fixed(NOW, ZoneOffset.UTC));
    private PaymentOrder order;

    @BeforeEach
    void setUp() {
        order = pendingOrder(100L);
        when(orders.findByTelegramInvoicePayload(order.getTelegramInvoicePayload()))
                .thenReturn(Optional.of(order));
        when(orders.findByTelegramInvoicePayloadForUpdate(order.getTelegramInvoicePayload()))
                .thenReturn(Optional.of(order));
        when(orders.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void acceptsValidAndRepeatedPreCheckout() {
        TelegramPreCheckoutCommand command = pre(100, "RUB", 14990,
                order.getTelegramInvoicePayload());
        assertThat(service.validatePreCheckout(command)).isTrue();
        assertThat(service.validatePreCheckout(command)).isTrue();
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void rejectsUnknownPayload() {
        assertThat(service.validatePreCheckout(pre(100, "RUB", 14990,
                "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"))).isFalse();
    }

    @Test
    void rejectsWrongUserAmountAndCurrency() {
        assertThat(service.validatePreCheckout(pre(101, "RUB", 14990,
                order.getTelegramInvoicePayload()))).isFalse();
        assertThat(service.validatePreCheckout(pre(100, "RUB", 14991,
                order.getTelegramInvoicePayload()))).isFalse();
        assertThat(service.validatePreCheckout(pre(100, "USD", 14990,
                order.getTelegramInvoicePayload()))).isFalse();
    }

    @Test
    void rejectsInvalidStatus() {
        order.markCanceled(NOW);
        assertThat(service.validatePreCheckout(pre(100, "RUB", 14990,
                order.getTelegramInvoicePayload()))).isFalse();
    }

    @Test
    void appliesSuccessfulPaymentAndStoresBothCharges() {
        service.applySuccessfulPayment(success("tg-1", "provider-1"));
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(order.getTelegramPaymentChargeId()).isEqualTo("tg-1");
        assertThat(order.getProviderPaymentChargeId()).isEqualTo("provider-1");
        assertThat(order.getActivationStatus()).isEqualTo(PaymentActivationStatus.PENDING);
    }

    @Test
    void duplicateSuccessfulPaymentIsNoOpIncludingAfterActivationStateChanged() {
        TelegramSuccessfulPaymentCommand command = success("tg-1", "provider-1");
        service.applySuccessfulPayment(command);
        service.applySuccessfulPayment(command);
        verify(orders, times(1)).saveAndFlush(order);
    }

    @Test
    void rejectsChargeCollisionWithAnotherOrder() {
        PaymentOrder other = pendingOrder(200L);
        when(orders.findByTelegramPaymentChargeId("tg-1"))
                .thenReturn(Optional.of(other));
        assertThatThrownBy(() -> service.applySuccessfulPayment(
                success("tg-1", "provider-1")))
                .isInstanceOf(PaymentProviderUncertainException.class);
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void rejectsSuccessfulPaymentMismatches() {
        assertThatThrownBy(() -> service.applySuccessfulPayment(new TelegramSuccessfulPaymentCommand(
                101, order.getTelegramInvoicePayload(), "RUB", 14990,
                "tg", "provider", NOW))).isInstanceOf(PaymentProviderUncertainException.class);
        assertThatThrownBy(() -> service.applySuccessfulPayment(new TelegramSuccessfulPaymentCommand(
                100, order.getTelegramInvoicePayload(), "USD", 14990,
                "tg", "provider", NOW))).isInstanceOf(PaymentProviderUncertainException.class);
        assertThatThrownBy(() -> service.applySuccessfulPayment(new TelegramSuccessfulPaymentCommand(
                100, order.getTelegramInvoicePayload(), "RUB", 1,
                "tg", "provider", NOW))).isInstanceOf(PaymentProviderUncertainException.class);
    }

    private TelegramPreCheckoutCommand pre(long user, String currency, long amount, String payload) {
        return new TelegramPreCheckoutCommand("q", user, payload, currency, amount);
    }

    private TelegramSuccessfulPaymentCommand success(String telegramCharge, String providerCharge) {
        return new TelegramSuccessfulPaymentCommand(
                100, order.getTelegramInvoicePayload(), "RUB", 14990,
                telegramCharge, providerCharge, NOW);
    }

    private PaymentOrder pendingOrder(long telegramId) {
        TelegramUser user = TelegramUser.builder().id(UUID.randomUUID())
                .telegramId(telegramId).chatId(telegramId + 1).role(UserRole.USER)
                .createdAt(NOW).updatedAt(NOW).build();
        VpnTariff tariff = VpnTariff.builder().id(UUID.randomUUID())
                .code("MONTH").name("Monthly").durationDays(30)
                .price(new BigDecimal("149.90")).currency("RUB").active(true)
                .createdAt(NOW).updatedAt(NOW).build();
        PaymentOrder result = PaymentOrder.create(user, tariff,
                PaymentProviderType.TELEGRAM_YOOKASSA, NOW.minusSeconds(10),
                Duration.ofHours(1));
        result.markCreating(NOW.minusSeconds(9));
        result.markTelegramInvoiceSent(42, NOW.minusSeconds(8));
        return result;
    }
}
