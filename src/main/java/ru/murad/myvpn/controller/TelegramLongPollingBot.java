package ru.murad.myvpn.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import ru.murad.myvpn.config.TelegramProperties;
import ru.murad.myvpn.dto.TelegramIncomingMessage;
import ru.murad.myvpn.dto.TelegramCallbackQuery;
import ru.murad.myvpn.dto.TelegramCommandResponse;
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

    @Autowired
    public TelegramLongPollingBot(
            TelegramProperties properties,
            TelegramCommandService commandService
    ) {
        this(properties, commandService,
                new OkHttpTelegramClient(properties.botToken()));
    }

    TelegramLongPollingBot(
            TelegramProperties properties,
            TelegramCommandService commandService,
            TelegramClient telegramClient
    ) {
        this.properties = properties;
        this.commandService = commandService;
        this.telegramClient = telegramClient;
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
        if (update.hasCallbackQuery()) {
            processCallback(update);
            return;
        }
        if (!update.hasMessage() || !update.getMessage().hasText()
                || update.getMessage().getFrom() == null) {
            return;
        }
        var message = update.getMessage();
        var from = message.getFrom();
        TelegramCommandResponse response = commandService.handleResponse(
                new TelegramIncomingMessage(
                from.getId(), message.getChatId(), from.getUserName(),
                from.getFirstName(), from.getLastName(), message.getText()));
        send(message.getChatId(), response);
    }

    private void processCallback(Update update) {
        var callback = update.getCallbackQuery();
        if (callback.getFrom() == null || callback.getMessage() == null) {
            answerCallback(callback.getId());
            return;
        }
        answerCallback(callback.getId());
        long chatId = callback.getMessage().getChatId();
        TelegramCommandResponse response;
        try {
            response = commandService.handleCallback(new TelegramCallbackQuery(
                    callback.getFrom().getId(), chatId, callback.getData()));
        } catch (RuntimeException exception) {
            response = TelegramCommandResponse.text(
                    "Не удалось выполнить действие. Повторите попытку позже.");
        }
        send(chatId, response);
    }

    private void send(long chatId, TelegramCommandResponse response) {
        try {
            var builder = SendMessage.builder()
                    .chatId(chatId)
                    .text(response.text());
            if (!response.keyboard().isEmpty()) {
                builder.replyMarkup(new InlineKeyboardMarkup(
                        response.keyboard().stream()
                                .map(row -> new InlineKeyboardRow(
                                        row.stream().map(button ->
                                                        InlineKeyboardButton.builder()
                                                                .text(button.text())
                                                                .callbackData(button.callbackData())
                                                                .url(button.url())
                                                                .build())
                                                .toList()))
                                .toList()));
            }
            telegramClient.execute(builder.build());
        } catch (TelegramApiException exception) {
            log.error("Failed to send Telegram response to chat {}", chatId);
        }
    }

    private void answerCallback(String callbackId) {
        try {
            telegramClient.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackId)
                    .build());
        } catch (TelegramApiException exception) {
            log.error("Failed to answer Telegram callback");
        }
    }
}
