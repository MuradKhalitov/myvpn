package ru.murad.myvpn.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery;
import org.telegram.telegrambots.meta.api.methods.invoices.SendInvoice;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import ru.murad.myvpn.config.TelegramPaymentProperties;
import ru.murad.myvpn.dto.TelegramInvoiceRequest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class TelegramBotApiPaymentGatewayTest {

    @Test
    void mapsInvoiceWithoutReceiptAndReturnsMessageId() throws Exception {
        TelegramClient client = mock(TelegramClient.class);
        Message message = mock(Message.class);
        when(message.getMessageId()).thenReturn(99);
        when(client.execute(any(SendInvoice.class))).thenReturn(message);
        var properties = new TelegramPaymentProperties(
                "secret-provider-token", "RUB", Duration.ofSeconds(9),
                false, "", "");
        var gateway = new TelegramBotApiPaymentGateway(properties, client);

        assertThat(gateway.sendInvoice(new TelegramInvoiceRequest(
                200, "MyVPN", "VPN service", "opaque", "RUB", 14990)))
                .isEqualTo(99);

        var captor = org.mockito.ArgumentCaptor.forClass(SendInvoice.class);
        verify(client).execute(captor.capture());
        SendInvoice invoice = captor.getValue();
        assertThat(invoice.getChatId()).isEqualTo("200");
        assertThat(invoice.getTitle()).isEqualTo("MyVPN");
        assertThat(invoice.getDescription()).isEqualTo("VPN service");
        assertThat(invoice.getPayload()).isEqualTo("opaque");
        assertThat(invoice.getProviderToken()).isEqualTo("secret-provider-token");
        assertThat(invoice.getCurrency()).isEqualTo("RUB");
        assertThat(invoice.getPrices()).singleElement()
                .satisfies(price -> assertThat(price.getAmount()).isEqualTo(14990));
        assertThat(properties.toString()).doesNotContain("secret-provider-token");
        assertThat(invoice.getProviderData()).isNull();
    }

    @Test
    void answersPreCheckoutWithSafeFailure() throws Exception {
        TelegramClient client = mock(TelegramClient.class);
        var gateway = new TelegramBotApiPaymentGateway(
                new TelegramPaymentProperties("token", "RUB", Duration.ofSeconds(9),
                        false, "", ""), client);
        gateway.answerPreCheckoutQuery("q", false, "safe");
        var captor = org.mockito.ArgumentCaptor.forClass(AnswerPreCheckoutQuery.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getOk()).isFalse();
        assertThat(captor.getValue().getErrorMessage()).isEqualTo("safe");
    }

    @Test
    void okFalseWithBadRequestIsPermanent() throws Exception {
        TelegramClient client = mock(TelegramClient.class);
        TelegramApiRequestException rejected = mock(TelegramApiRequestException.class);
        when(rejected.getErrorCode()).thenReturn(400);
        when(rejected.getApiResponse()).thenReturn("Bad Request: provider token is invalid");
        when(client.execute(any(SendInvoice.class))).thenThrow(rejected);

        assertThatThrownBy(() -> gateway(client).sendInvoice(request()))
                .isInstanceOf(ru.murad.myvpn.exception.PaymentProviderPermanentException.class);
    }

    @Test
    void unauthorizedProviderTokenIsPermanent() throws Exception {
        TelegramClient client = mock(TelegramClient.class);
        TelegramApiRequestException rejected = mock(TelegramApiRequestException.class);
        when(rejected.getErrorCode()).thenReturn(401);
        when(rejected.getApiResponse()).thenReturn("Unauthorized");
        when(client.execute(any(SendInvoice.class))).thenThrow(rejected);

        assertThatThrownBy(() -> gateway(client).sendInvoice(request()))
                .isInstanceOf(ru.murad.myvpn.exception.PaymentProviderPermanentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 409, 429, 500, 503})
    void retryableTelegramStatusesAreUncertain(int errorCode) throws Exception {
        TelegramClient client = mock(TelegramClient.class);
        TelegramApiRequestException rejected = mock(TelegramApiRequestException.class);
        when(rejected.getErrorCode()).thenReturn(errorCode);
        when(rejected.getApiResponse()).thenReturn("temporary Telegram error");
        when(client.execute(any(SendInvoice.class))).thenThrow(rejected);

        assertThatThrownBy(() -> gateway(client).sendInvoice(request()))
                .isInstanceOf(ru.murad.myvpn.exception.PaymentProviderUncertainException.class);
    }

    @Test
    void timeoutIsUncertain() throws Exception {
        TelegramClient client = mock(TelegramClient.class);
        when(client.execute(any(SendInvoice.class)))
                .thenThrow(new TelegramApiException("timeout"));

        assertThatThrownBy(() -> gateway(client).sendInvoice(request()))
                .isInstanceOf(ru.murad.myvpn.exception.PaymentProviderUncertainException.class);
    }

    @Test
    void uncheckedClientFailureIsUncertain() throws Exception {
        TelegramClient client = mock(TelegramClient.class);
        when(client.execute(any(SendInvoice.class)))
                .thenThrow(new IllegalStateException("network unavailable"));

        assertThatThrownBy(() -> gateway(client).sendInvoice(request()))
                .isInstanceOf(ru.murad.myvpn.exception.PaymentProviderUncertainException.class);
    }

    @Test
    void incompleteSuccessfulResponseIsUncertain() throws Exception {
        TelegramClient client = mock(TelegramClient.class);
        when(client.execute(any(SendInvoice.class))).thenReturn(null);

        assertThatThrownBy(() -> gateway(client).sendInvoice(request()))
                .isInstanceOf(ru.murad.myvpn.exception.PaymentProviderUncertainException.class);
    }

    @Test
    void diagnosticsRedactProviderTokenAndFullPayload(
            CapturedOutput output
    ) throws Exception {
        TelegramClient client = mock(TelegramClient.class);
        TelegramApiRequestException rejected = mock(TelegramApiRequestException.class);
        when(rejected.getErrorCode()).thenReturn(400);
        when(rejected.getApiResponse()).thenReturn(
                "Bad Request secret-provider-token opaque");
        when(client.execute(any(SendInvoice.class))).thenThrow(rejected);

        assertThatThrownBy(() -> gateway(client).sendInvoice(request()))
                .isInstanceOf(ru.murad.myvpn.exception.PaymentProviderPermanentException.class);
        assertThat(output).doesNotContain("secret-provider-token", "opaque");
        assertThat(output).contains("[REDACTED]");
    }

    private TelegramBotApiPaymentGateway gateway(TelegramClient client) {
        return new TelegramBotApiPaymentGateway(
                new TelegramPaymentProperties(
                        "secret-provider-token", "RUB", Duration.ofSeconds(9),
                        false, "", ""),
                client);
    }

    private TelegramInvoiceRequest request() {
        return new TelegramInvoiceRequest(
                200, "MyVPN", "VPN service", "opaque", "RUB", 14990);
    }
}
