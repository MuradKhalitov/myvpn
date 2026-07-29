package ru.murad.myvpn.controller;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery;
import org.telegram.telegrambots.meta.api.methods.invoices.SendInvoice;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import ru.murad.myvpn.config.TelegramPaymentProperties;
import ru.murad.myvpn.dto.TelegramInvoiceRequest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

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
}
