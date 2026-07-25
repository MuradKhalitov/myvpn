package ru.murad.myvpn.dto;

public record TelegramCallbackQuery(
        long telegramId,
        long chatId,
        String data
) {
    @Override
    public String toString() {
        return "TelegramCallbackQuery[redacted]";
    }
}
