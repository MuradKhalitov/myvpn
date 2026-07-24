package ru.murad.myvpn.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "telegram_users")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class TelegramUser {

    @Id
    private UUID id;

    @Column(name = "telegram_id", nullable = false, unique = true)
    private long telegramId;

    @Column(name = "chat_id", nullable = false)
    private long chatId;

    @Column(length = 32)
    private String username;

    @Column(name = "first_name", length = 64)
    private String firstName;

    @Column(name = "last_name", length = 64)
    private String lastName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UserRole role;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public void updateProfile(
            long chatId,
            String username,
            String firstName,
            String lastName,
            Instant updatedAt
    ) {
        this.chatId = chatId;
        this.username = username;
        this.firstName = firstName;
        this.lastName = lastName;
        this.updatedAt = updatedAt;
    }
}
