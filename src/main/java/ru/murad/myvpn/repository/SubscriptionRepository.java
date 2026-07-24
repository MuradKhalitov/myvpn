package ru.murad.myvpn.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.murad.myvpn.model.Subscription;
import ru.murad.myvpn.model.SubscriptionStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    Optional<Subscription> findFirstByUserIdAndStatusOrderByExpiresAtDesc(
            UUID userId,
            SubscriptionStatus status
    );

    Optional<Subscription> findFirstByUserTelegramIdAndStatusAndExpiresAtAfterOrderByExpiresAtDesc(
            long telegramId,
            SubscriptionStatus status,
            Instant now
    );
}
