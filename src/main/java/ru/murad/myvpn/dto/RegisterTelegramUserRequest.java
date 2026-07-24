package ru.murad.myvpn.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record RegisterTelegramUserRequest(
        @Positive long telegramId,
        @Positive long chatId,
        @Size(max = 32) String username,
        @Size(max = 64) String firstName,
        @Size(max = 64) String lastName
) {
}
