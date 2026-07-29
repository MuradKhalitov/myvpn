package ru.murad.myvpn.dto;

import java.time.Instant;

public record TelegramSuccessfulPaymentCommand(
        long telegramUserId,
        String payload,
        String currency,
        long totalAmount,
        String telegramPaymentChargeId,
        String providerPaymentChargeId,
        Instant receivedAt
) {
}
