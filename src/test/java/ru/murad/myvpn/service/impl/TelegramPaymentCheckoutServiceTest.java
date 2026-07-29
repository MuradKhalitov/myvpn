package ru.murad.myvpn.service.impl;

import org.junit.jupiter.api.Test;
import ru.murad.myvpn.config.PaymentProperties;
import ru.murad.myvpn.dto.CheckoutDestination;
import ru.murad.myvpn.dto.PaymentCheckoutResult;
import ru.murad.myvpn.dto.PreparedCheckout;
import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.model.PaymentStatus;
import ru.murad.myvpn.repository.PaymentOrderRepository;
import ru.murad.myvpn.repository.TelegramUserRepository;
import ru.murad.myvpn.service.*;

import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TelegramPaymentCheckoutServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-29T10:00:00Z");

    @Test
    void sendsInvoiceAndReturnsExplicitDestination() {
        PaymentCheckoutTransactionService transactions =
                mock(PaymentCheckoutTransactionService.class);
        TelegramInvoiceProvider invoices = mock(TelegramInvoiceProvider.class);
        PreparedCheckout prepared = prepared(PaymentStatus.CREATING, null);
        when(transactions.prepareCheckout(anyLong(), anyString(), any(), any(), any()))
                .thenReturn(prepared);
        when(invoices.sendInvoice(prepared)).thenReturn(55);
        PaymentCheckoutResult applied = new PaymentCheckoutResult(
                prepared.orderId(), prepared.tariffName(), prepared.amount(),
                "RUB", 30, PaymentStatus.PENDING,
                new CheckoutDestination.TelegramInvoiceSent(55), prepared.localExpiresAt());
        when(transactions.applyTelegramInvoice(prepared, 55, NOW)).thenReturn(applied);
        PaymentCheckoutServiceImpl service = service(transactions, invoices);

        PaymentCheckoutResult result = service.startCheckout(100, "MONTH");

        assertThat(result.destination())
                .isEqualTo(new CheckoutDestination.TelegramInvoiceSent(55));
        verify(invoices).sendInvoice(prepared);
        verifyNoInteractions(mock(PaymentProviderRegistry.class));
    }

    @Test
    void repeatedPendingCheckoutReusesInvoiceAndDoesNotSendAgain() {
        PaymentCheckoutTransactionService transactions =
                mock(PaymentCheckoutTransactionService.class);
        TelegramInvoiceProvider invoices = mock(TelegramInvoiceProvider.class);
        PreparedCheckout prepared = prepared(PaymentStatus.PENDING, 55);
        when(transactions.prepareCheckout(anyLong(), anyString(), any(), any(), any()))
                .thenReturn(prepared);
        PaymentCheckoutResult result = service(transactions, invoices)
                .startCheckout(100, "MONTH");
        assertThat(result.destination())
                .isEqualTo(new CheckoutDestination.TelegramInvoiceSent(55));
        verifyNoInteractions(invoices);
    }

    @Test
    void missingInvoiceProviderMarksCreatingOrderFailed() {
        PaymentCheckoutTransactionService transactions =
                mock(PaymentCheckoutTransactionService.class);
        PreparedCheckout prepared = prepared(PaymentStatus.CREATING, null);
        when(transactions.prepareCheckout(anyLong(), anyString(), any(), any(), any()))
                .thenReturn(prepared);
        PaymentCheckoutServiceImpl service = new PaymentCheckoutServiceImpl(
                mock(TelegramUserRepository.class), mock(PaymentOrderRepository.class),
                mock(PaymentProviderRegistry.class), transactions,
                new PaymentProperties(PaymentProviderType.TELEGRAM_YOOKASSA,
                        Duration.ofHours(1), URI.create("https://example.test"), false),
                Clock.fixed(NOW, ZoneOffset.UTC), Optional.empty());

        assertThatThrownBy(() -> service.startCheckout(100, "MONTH"))
                .isInstanceOf(ru.murad.myvpn.exception.PaymentProviderPermanentException.class);
        verify(transactions).markPermanentFailure(prepared, NOW);
    }

    @Test
    void timeoutMarksCreatingOrderForManualReview() {
        PaymentCheckoutTransactionService transactions =
                mock(PaymentCheckoutTransactionService.class);
        TelegramInvoiceProvider invoices = mock(TelegramInvoiceProvider.class);
        PreparedCheckout prepared = prepared(PaymentStatus.CREATING, null);
        when(transactions.prepareCheckout(anyLong(), anyString(), any(), any(), any()))
                .thenReturn(prepared);
        when(invoices.sendInvoice(prepared)).thenThrow(
                new ru.murad.myvpn.exception.PaymentProviderUncertainException("timeout"));

        assertThatThrownBy(() -> service(transactions, invoices)
                .startCheckout(100, "MONTH"))
                .isInstanceOf(ru.murad.myvpn.exception.PaymentProviderUncertainException.class);
        verify(transactions).markTelegramInvoiceUncertain(prepared, NOW);
        verify(transactions, never()).markPermanentFailure(any(), any());
    }

    @Test
    void sendInvoiceRunsWithoutActiveDatabaseTransaction() {
        PaymentCheckoutTransactionService transactions =
                mock(PaymentCheckoutTransactionService.class);
        TelegramInvoiceProvider invoices = mock(TelegramInvoiceProvider.class);
        PreparedCheckout prepared = prepared(PaymentStatus.CREATING, null);
        when(transactions.prepareCheckout(anyLong(), anyString(), any(), any(), any()))
                .thenReturn(prepared);
        when(invoices.sendInvoice(prepared)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager
                    .isActualTransactionActive()).isFalse();
            return 55;
        });
        when(transactions.applyTelegramInvoice(prepared, 55, NOW))
                .thenReturn(new PaymentCheckoutResult(
                        prepared.orderId(), prepared.tariffName(), prepared.amount(),
                        "RUB", 30, PaymentStatus.PENDING,
                        new CheckoutDestination.TelegramInvoiceSent(55),
                        prepared.localExpiresAt()));

        service(transactions, invoices).startCheckout(100, "MONTH");
    }

    private PaymentCheckoutServiceImpl service(
            PaymentCheckoutTransactionService transactions,
            TelegramInvoiceProvider invoices
    ) {
        return new PaymentCheckoutServiceImpl(
                mock(TelegramUserRepository.class), mock(PaymentOrderRepository.class),
                mock(PaymentProviderRegistry.class), transactions,
                new PaymentProperties(PaymentProviderType.TELEGRAM_YOOKASSA,
                        Duration.ofHours(1), URI.create("https://example.test"), false),
                Clock.fixed(NOW, ZoneOffset.UTC), Optional.of(invoices));
    }

    private PreparedCheckout prepared(PaymentStatus status, Integer messageId) {
        return new PreparedCheckout(
                UUID.randomUUID(), UUID.randomUUID(), 100, 200,
                UUID.randomUUID(), PaymentProviderType.TELEGRAM_YOOKASSA,
                UUID.randomUUID(), new BigDecimal("149.90"), "RUB",
                "MONTH", "Monthly", 30, status, null,
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", messageId,
                NOW.plusSeconds(3600));
    }
}
