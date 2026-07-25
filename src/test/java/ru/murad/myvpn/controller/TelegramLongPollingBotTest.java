package ru.murad.myvpn.controller;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import ru.murad.myvpn.config.TelegramProperties;
import ru.murad.myvpn.service.TelegramCommandService;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
