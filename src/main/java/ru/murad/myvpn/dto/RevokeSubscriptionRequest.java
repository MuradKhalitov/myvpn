package ru.murad.myvpn.dto;

import jakarta.validation.constraints.Positive;

public record RevokeSubscriptionRequest(
        @Positive long administratorTelegramId,
        @Positive long userTelegramId
) {
}
