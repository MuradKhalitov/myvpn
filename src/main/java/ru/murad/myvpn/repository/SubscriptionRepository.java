package ru.murad.myvpn.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import ru.murad.myvpn.model.Subscription;
import ru.murad.myvpn.model.SubscriptionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    Optional<Subscription> findFirstByUserIdAndStatusOrderByExpiresAtDesc(
            UUID userId,
            SubscriptionStatus status
    );

    boolean existsByUserIdAndStatus(UUID userId, SubscriptionStatus status);

    List<Subscription> findAllByStatus(SubscriptionStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select subscription from Subscription subscription where subscription.id = :id")
    Optional<Subscription> findByIdForUpdate(@Param("id") UUID id);

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

    List<Subscription> findAllByStatusAndUpdatedAtLessThanEqual(
            SubscriptionStatus status,
            Instant threshold
    );

    @Query(value = """
            SELECT *
            FROM subscriptions
            WHERE status IN ('PENDING', 'RECONCILIATION_REQUIRED')
              AND ((status = 'PENDING' AND updated_at <= :staleBefore)
                   OR status = 'RECONCILIATION_REQUIRED')
              AND (provisioning_lease_until IS NULL OR provisioning_lease_until <= :now)
              AND (next_provisioning_attempt_at IS NULL
                   OR next_provisioning_attempt_at <= :now)
              AND provisioning_attempt_count < :maxAttempts
            ORDER BY updated_at
            FOR UPDATE SKIP LOCKED
            LIMIT :batchSize
            """, nativeQuery = true)
    List<Subscription> lockProvisioningCandidates(
            @Param("staleBefore") Instant staleBefore,
            @Param("now") Instant now,
            @Param("maxAttempts") int maxAttempts,
            @Param("batchSize") int batchSize
    );
}
