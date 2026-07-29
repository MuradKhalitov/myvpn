package ru.murad.myvpn.controller;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import ru.murad.myvpn.config.TelegramProperties;
import ru.murad.myvpn.service.TelegramCommandService;
import ru.murad.myvpn.service.TelegramPaymentEventService;
import ru.murad.myvpn.dto.TelegramPreCheckoutCommand;
import ru.murad.myvpn.dto.TelegramSuccessfulPaymentCommand;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TelegramLongPollingBotTest {

    @Test
    void callbackMustBeAnsweredEvenWhenServiceThrowsUnexpectedException()
            throws Exception {
        TelegramCommandService service = mock(TelegramCommandService.class);
        TelegramClient client = mock(TelegramClient.class);
        Update update = callbackUpdate();
        when(service.handleCallback(any()))
                .thenThrow(new IllegalStateException("internal details"));
        TelegramLongPollingBot bot = new TelegramLongPollingBot(
                new TelegramProperties(Set.of(), "test-token"),
                service,
                client);

        bot.consume(List.of(update));

        verify(client, atLeastOnce()).execute(any(AnswerCallbackQuery.class));
    }

    @Test
    void routesPreCheckoutBeforeCommands() {
        TelegramCommandService commands = mock(TelegramCommandService.class);
        TelegramPaymentEventService payments = mock(TelegramPaymentEventService.class);
        Update update = mock(Update.class, RETURNS_DEEP_STUBS);
        when(update.hasPreCheckoutQuery()).thenReturn(true);
        when(update.getPreCheckoutQuery().getId()).thenReturn("q");
        when(update.getPreCheckoutQuery().getFrom().getId()).thenReturn(100L);
        when(update.getPreCheckoutQuery().getInvoicePayload()).thenReturn("payload");
        when(update.getPreCheckoutQuery().getCurrency()).thenReturn("RUB");
        when(update.getPreCheckoutQuery().getTotalAmount()).thenReturn(14990);
        TelegramLongPollingBot bot = new TelegramLongPollingBot(
                new TelegramProperties(Set.of(), "test-token"), commands,
                mock(TelegramClient.class), Optional.of(payments), Clock.systemUTC());

        bot.consume(List.of(update));

        verify(payments).handlePreCheckout(new TelegramPreCheckoutCommand(
                "q", 100L, "payload", "RUB", 14990));
        verifyNoInteractions(commands);
    }

    @Test
    void routesSuccessfulPaymentBeforeTextAndContinuesAfterBadUpdate() {
        TelegramCommandService commands = mock(TelegramCommandService.class);
        TelegramPaymentEventService payments = mock(TelegramPaymentEventService.class);
        Update paymentUpdate = mock(Update.class, RETURNS_DEEP_STUBS);
        when(paymentUpdate.hasMessage()).thenReturn(true);
        when(paymentUpdate.getMessage().hasSuccessfulPayment()).thenReturn(true);
        when(paymentUpdate.getMessage().getFrom().getId()).thenReturn(100L);
        when(paymentUpdate.getMessage().getSuccessfulPayment().getInvoicePayload())
                .thenReturn("payload");
        when(paymentUpdate.getMessage().getSuccessfulPayment().getCurrency()).thenReturn("RUB");
        when(paymentUpdate.getMessage().getSuccessfulPayment().getTotalAmount()).thenReturn(14990);
        when(paymentUpdate.getMessage().getSuccessfulPayment().getTelegramPaymentChargeId())
                .thenReturn("tg");
        when(paymentUpdate.getMessage().getSuccessfulPayment().getProviderPaymentChargeId())
                .thenReturn("provider");
        Instant now = Instant.parse("2026-07-29T10:00:00Z");
        TelegramLongPollingBot bot = new TelegramLongPollingBot(
                new TelegramProperties(Set.of(), "test-token"), commands,
                mock(TelegramClient.class), Optional.of(payments),
                Clock.fixed(now, ZoneOffset.UTC));

        bot.consume(List.of(paymentUpdate));

        verify(payments).handleSuccessfulPayment(new TelegramSuccessfulPaymentCommand(
                100L, "payload", "RUB", 14990, "tg", "provider", now));
        verifyNoInteractions(commands);
    }

    private Update callbackUpdate() {
        Update update = mock(Update.class, RETURNS_DEEP_STUBS);
        when(update.hasCallbackQuery()).thenReturn(true);
        when(update.getCallbackQuery().getId()).thenReturn("callback-id");
        when(update.getCallbackQuery().getFrom().getId()).thenReturn(100L);
        when(update.getCallbackQuery().getMessage().getChatId()).thenReturn(200L);
        when(update.getCallbackQuery().getData()).thenReturn("buy:MONTH_1");
        return update;
    }
}
