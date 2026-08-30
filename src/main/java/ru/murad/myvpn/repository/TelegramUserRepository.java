package ru.murad.myvpn.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.murad.myvpn.model.TelegramUser;

import java.util.Optional;
import java.util.UUID;

public interface TelegramUserRepository extends JpaRepository<TelegramUser, UUID> {

    Optional<TelegramUser> findByTelegramId(long telegramId);

    @Query(value = "select 1 from pg_advisory_xact_lock(:telegramId)", nativeQuery = true)
    int acquireRegistrationLock(@Param("telegramId") long telegramId);
}
