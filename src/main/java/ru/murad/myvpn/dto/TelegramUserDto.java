package ru.murad.myvpn.dto;

import ru.murad.myvpn.model.UserRole;

import java.time.Instant;
import java.util.UUID;

public record TelegramUserDto(
        UUID id,
        long telegramId,
        long chatId,
        String username,
        String firstName,
        String lastName,
        UserRole role,
        Instant createdAt,
        Instant updatedAt
) {
}
