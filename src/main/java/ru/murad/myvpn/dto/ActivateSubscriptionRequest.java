package ru.murad.myvpn.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record ActivateSubscriptionRequest(
        @Positive long administratorTelegramId,
        @Positive long userTelegramId,
        @NotBlank String tariffCode
) {
}
