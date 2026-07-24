package ru.murad.myvpn.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.murad.myvpn.model.Subscription;
import ru.murad.myvpn.model.SubscriptionStatus;

import java.time.Instant;
import java.util.List;
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

    Optional<Subscription> findFirstByUserTelegramIdAndStatusOrderByExpiresAtDesc(
            long telegramId,
            SubscriptionStatus status
    );

    List<Subscription> findAllByStatusAndExpiresAtLessThanEqual(
            SubscriptionStatus status,
            Instant now
    );
}
