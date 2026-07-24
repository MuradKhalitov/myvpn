package ru.murad.myvpn.dto;

public record TelegramIncomingMessage(
        long telegramId,
        long chatId,
        String username,
        String firstName,
        String lastName,
        String text
) {
}
