package ru.murad.myvpn.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.DependsOn;
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
import ru.murad.myvpn.service.TelegramPaymentEventService;
import ru.murad.myvpn.dto.TelegramPreCheckoutCommand;
import ru.murad.myvpn.dto.TelegramSuccessfulPaymentCommand;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
@Profile("!test")
@DependsOn("telegramLongPollingInstanceLock")
public class TelegramLongPollingBot
        implements SpringLongPollingBot, LongPollingUpdateConsumer {

    private final TelegramProperties properties;
    private final TelegramCommandService commandService;
    private final TelegramClient telegramClient;
    private final Optional<TelegramPaymentEventService> paymentEventService;
    private final Clock clock;

    @Autowired
    public TelegramLongPollingBot(
            TelegramProperties properties,
            TelegramCommandService commandService,
            Optional<TelegramPaymentEventService> paymentEventService,
            Clock clock
    ) {
        this(properties, commandService, new OkHttpTelegramClient(properties.botToken()),
                paymentEventService, clock);
    }

    TelegramLongPollingBot(
            TelegramProperties properties,
            TelegramCommandService commandService,
            TelegramClient telegramClient
    ) {
        this(properties, commandService, telegramClient, Optional.empty(),
                Clock.systemUTC());
    }

    TelegramLongPollingBot(
            TelegramProperties properties,
            TelegramCommandService commandService,
            TelegramClient telegramClient,
            Optional<TelegramPaymentEventService> paymentEventService,
            Clock clock
    ) {
        this.properties = properties;
        this.commandService = commandService;
        this.telegramClient = telegramClient;
        this.paymentEventService = paymentEventService;
        this.clock = clock;
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
        updates.forEach(update -> {
            try {
                processUpdate(update);
            } catch (RuntimeException exception) {
                log.error("Failed to process Telegram update exceptionType={} stackTrace={}",
                        exception.getClass().getName(),
                        java.util.Arrays.toString(exception.getStackTrace()));
            }
        });
    }

    private void processUpdate(Update update) {
        if (update.hasPreCheckoutQuery()) {
            processPreCheckout(update);
            return;
        }
        if (update.hasMessage() && update.getMessage().hasSuccessfulPayment()) {
            processSuccessfulPayment(update);
            return;
        }
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

    private void processPreCheckout(Update update) {
        var query = update.getPreCheckoutQuery();
        if (query.getFrom() == null || paymentEventService.isEmpty()) {
            if (paymentEventService.isPresent()) {
                paymentEventService.get().handlePreCheckout(new TelegramPreCheckoutCommand(
                        query.getId(), 0L, query.getInvoicePayload(),
                        query.getCurrency(), query.getTotalAmount()));
            }
            return;
        }
        paymentEventService.get().handlePreCheckout(new TelegramPreCheckoutCommand(
                query.getId(), query.getFrom().getId(), query.getInvoicePayload(),
                query.getCurrency(), query.getTotalAmount()));
    }

    private void processSuccessfulPayment(Update update) {
        var message = update.getMessage();
        if (message.getFrom() == null || paymentEventService.isEmpty()) return;
        var payment = message.getSuccessfulPayment();
        paymentEventService.get().handleSuccessfulPayment(
                new TelegramSuccessfulPaymentCommand(
                        message.getFrom().getId(), payment.getInvoicePayload(),
                        payment.getCurrency(), payment.getTotalAmount(),
                        payment.getTelegramPaymentChargeId(),
                        payment.getProviderPaymentChargeId(), clock.instant()));
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
            log.error("Failed to process Telegram callback exceptionType={} stackTrace={}",
                    exception.getClass().getName(),
                    java.util.Arrays.toString(exception.getStackTrace()));
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
            log.error("Failed to send Telegram response exceptionType={} stackTrace={}",
                    exception.getClass().getName(),
                    java.util.Arrays.toString(exception.getStackTrace()));
        }
    }

    private void answerCallback(String callbackId) {
        try {
            telegramClient.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackId)
                    .build());
        } catch (TelegramApiException exception) {
            log.error("Failed to answer Telegram callback exceptionType={} stackTrace={}",
                    exception.getClass().getName(),
                    java.util.Arrays.toString(exception.getStackTrace()));
        }
    }
}
