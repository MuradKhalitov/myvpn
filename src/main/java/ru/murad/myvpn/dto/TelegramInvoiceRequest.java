package ru.murad.myvpn.dto;

public record TelegramInvoiceRequest(
        long chatId,
        String title,
        String description,
        String payload,
        String currency,
        long amountMinor
) {
}
