package ru.murad.myvpn.controller;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import ru.murad.myvpn.config.TelegramProperties;
import ru.murad.myvpn.dto.VpnDeliveryMessage;
import ru.murad.myvpn.exception.TelegramDeliveryPermanentException;
import ru.murad.myvpn.exception.TelegramDeliveryTransientException;
import ru.murad.myvpn.model.VpnDeliveryFailureCode;
import ru.murad.myvpn.service.VpnConfigurationDeliveryGateway;

/**
 * Telegram-specific adapter. It logs neither content nor recipient identity.
 */
@Component
@Profile("local")
public class TelegramVpnConfigurationDeliveryGateway implements VpnConfigurationDeliveryGateway {

    private final org.telegram.telegrambots.meta.generics.TelegramClient client;

    @Autowired
    public TelegramVpnConfigurationDeliveryGateway(TelegramProperties properties) {
        this.client = new OkHttpTelegramClient(properties.botToken());
    }

    TelegramVpnConfigurationDeliveryGateway(
        org.telegram.telegrambots.meta.generics.TelegramClient client) {
        this.client = client;
    }

    @Override
    public Long deliver(VpnDeliveryMessage message) {
        if (message.text().length() > 4096) {
            throw new TelegramDeliveryPermanentException(
                VpnDeliveryFailureCode.TELEGRAM_MESSAGE_TOO_LONG);
        }
        try {
            return client.execute(
                    SendMessage.builder().chatId(message.telegramId()).text(message.text()).build())
                .getMessageId().longValue();
        } catch (TelegramApiException exception) {
            throw mapped(exception);
        }
    }

    static RuntimeException mapped(TelegramApiException exception) {
        if (exception instanceof TelegramApiRequestException request) {
            Integer status = request.getErrorCode();
            if (Integer.valueOf(429).equals(status)) {
                Integer seconds = request.getParameters() == null ? null
                    : request.getParameters().getRetryAfter();
                return new TelegramDeliveryTransientException(
                    seconds != null && seconds > 0 ? Duration.ofSeconds(seconds) : null,
                    VpnDeliveryFailureCode.TELEGRAM_RATE_LIMITED);
            }
            if (status != null && status >= 500 && status <= 599) {
                return new TelegramDeliveryTransientException(null,
                    VpnDeliveryFailureCode.TELEGRAM_UNAVAILABLE);
            }
            String description =
                request.getMessage() == null ? "" : request.getMessage().toLowerCase(Locale.ROOT);
            if (description.contains("blocked by the user") || description.contains(
                "user is deactivated")) {
                return new TelegramDeliveryPermanentException(
                    VpnDeliveryFailureCode.TELEGRAM_BOT_BLOCKED);
            }
            if (description.contains("chat not found") || description.contains("invalid chat")) {
                return new TelegramDeliveryPermanentException(
                    VpnDeliveryFailureCode.TELEGRAM_CHAT_NOT_FOUND);
            }
            if (description.contains("message is too long")) {
                return new TelegramDeliveryPermanentException(
                    VpnDeliveryFailureCode.TELEGRAM_MESSAGE_TOO_LONG);
            }
            if (status != null && status >= 400 && status <= 499) {
                return new TelegramDeliveryPermanentException(
                    VpnDeliveryFailureCode.TELEGRAM_REJECTED);
            }
        }
        if (hasIoCause(exception)) {
            return new TelegramDeliveryTransientException(null,
                VpnDeliveryFailureCode.TELEGRAM_UNAVAILABLE);
        }
        return new TelegramDeliveryTransientException(null,
            VpnDeliveryFailureCode.TELEGRAM_UNAVAILABLE);
    }

    private static boolean hasIoCause(Throwable failure) {
        for (Throwable cursor = failure; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof IOException) {
                return true;
            }
        }
        return false;
    }
}
