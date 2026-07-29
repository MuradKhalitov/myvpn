package ru.murad.myvpn.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.murad.myvpn.dto.PreparedCheckout;
import ru.murad.myvpn.dto.TelegramInvoiceRequest;
import ru.murad.myvpn.exception.PaymentProviderPermanentException;
import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.model.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramInvoiceProviderTest {

    private final TelegramPaymentGateway gateway = mock(TelegramPaymentGateway.class);
    private final TelegramInvoiceProvider provider = new TelegramInvoiceProvider(gateway);

    @Test
    void sendsRubInvoiceInKopecksWithStablePayload() {
        when(gateway.sendInvoice(any())).thenReturn(77);
        PreparedCheckout checkout = checkout("149.90", "RUB");

        assertThat(provider.sendInvoice(checkout)).isEqualTo(77);

        var captor = org.mockito.ArgumentCaptor.forClass(TelegramInvoiceRequest.class);
        verify(gateway).sendInvoice(captor.capture());
        assertThat(captor.getValue().chatId()).isEqualTo(200L);
        assertThat(captor.getValue().amountMinor()).isEqualTo(14990L);
        assertThat(captor.getValue().currency()).isEqualTo("RUB");
        assertThat(captor.getValue().payload()).isEqualTo(checkout.telegramInvoicePayload());
        assertThat(captor.getValue().title()).contains("Monthly");
        assertThat(captor.getValue().description()).contains("30");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.00", "-1.00", "1.001", "21474836.48"})
    void rejectsInvalidAmount(String amount) {
        assertThatThrownBy(() -> provider.sendInvoice(checkout(amount, "RUB")))
                .isInstanceOf(PaymentProviderPermanentException.class);
    }

    @Test
    void rejectsNonRub() {
        assertThatThrownBy(() -> provider.sendInvoice(checkout("1.00", "USD")))
                .isInstanceOf(PaymentProviderPermanentException.class);
    }

    private PreparedCheckout checkout(String amount, String currency) {
        return new PreparedCheckout(
                UUID.randomUUID(), UUID.randomUUID(), 100L, 200L,
                UUID.randomUUID(), PaymentProviderType.TELEGRAM_YOOKASSA,
                UUID.randomUUID(), new BigDecimal(amount), currency,
                "MONTH", "Monthly", 30, PaymentStatus.CREATING, null,
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", null,
                Instant.parse("2026-07-29T12:00:00Z"));
    }
}
