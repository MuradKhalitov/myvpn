package ru.murad.myvpn.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.murad.myvpn.model.TelegramUser;

import java.util.Optional;
import java.util.UUID;

public interface TelegramUserRepository extends JpaRepository<TelegramUser, UUID> {

    Optional<TelegramUser> findByTelegramId(long telegramId);
}
