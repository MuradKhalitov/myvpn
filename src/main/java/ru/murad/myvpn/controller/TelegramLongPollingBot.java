package ru.murad.myvpn.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import ru.murad.myvpn.config.TelegramProperties;
import ru.murad.myvpn.dto.TelegramIncomingMessage;
import ru.murad.myvpn.service.TelegramCommandService;

import java.util.List;

@Slf4j
@Component
@Profile("local")
public class TelegramLongPollingBot
        implements SpringLongPollingBot, LongPollingUpdateConsumer {

    private final TelegramProperties properties;
    private final TelegramCommandService commandService;
    private final TelegramClient telegramClient;

    public TelegramLongPollingBot(
            TelegramProperties properties,
            TelegramCommandService commandService
    ) {
        this.properties = properties;
        this.commandService = commandService;
        this.telegramClient = new OkHttpTelegramClient(properties.botToken());
    }

    @Override
    public String getBotToken() {
        return properties.botToken();
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    @Override
    public void consume(List<Update> updates) {
        updates.forEach(this::processUpdate);
    }

    private void processUpdate(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()
                || update.getMessage().getFrom() == null) {
            return;
        }
        var message = update.getMessage();
        var from = message.getFrom();
        String response = commandService.handle(new TelegramIncomingMessage(
                from.getId(), message.getChatId(), from.getUserName(),
                from.getFirstName(), from.getLastName(), message.getText()));
        try {
            telegramClient.execute(SendMessage.builder()
                    .chatId(message.getChatId())
                    .text(response)
                    .build());
        } catch (TelegramApiException exception) {
            log.error("Failed to send Telegram response to chat {}", message.getChatId());
        }
    }
}
