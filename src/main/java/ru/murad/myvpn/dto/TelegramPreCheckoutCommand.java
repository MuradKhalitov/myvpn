package ru.murad.myvpn.dto;

public record TelegramPreCheckoutCommand(
        String queryId, long telegramUserId, String payload,
        String currency, long totalAmount
) {
}
