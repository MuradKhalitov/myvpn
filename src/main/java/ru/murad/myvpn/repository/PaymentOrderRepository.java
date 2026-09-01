package ru.murad.myvpn.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.murad.myvpn.model.PaymentOrder;
import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.model.PaymentStatus;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.time.Instant;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, UUID> {

    Optional<PaymentOrder> findByProviderAndProviderPaymentId(
            PaymentProviderType provider,
            String providerPaymentId
    );

    Optional<PaymentOrder> findByIdempotenceKey(UUID idempotenceKey);

    Optional<PaymentOrder> findFirstByAccountIdAndStatusIn(
            UUID accountId,
            Collection<PaymentStatus> statuses
    );

    default Optional<PaymentOrder> findOpenByAccount(UUID accountId) {
        return findFirstByAccountIdAndStatusIn(
                accountId,
                EnumSet.of(
                        PaymentStatus.NEW,
                        PaymentStatus.CREATING,
                        PaymentStatus.PENDING,
                        PaymentStatus.MANUAL_REVIEW_REQUIRED));
    }

    @Query("select p from PaymentOrder p where p.account.id = :accountId order by p.createdAt desc")
    List<PaymentOrder> findAllByAccountOrderByCreatedAtDesc(@Param("accountId") UUID accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select paymentOrder from PaymentOrder paymentOrder where paymentOrder.id = :id")
    Optional<PaymentOrder> findByIdForUpdate(@Param("id") UUID id);


    @Query(value = """
            SELECT * FROM payment_orders
            WHERE status = 'SUCCEEDED'
              AND ((activation_status IN ('PENDING','RETRY_REQUIRED')
                    AND (next_activation_at IS NULL OR next_activation_at <= :now)
                    AND (activation_lease_until IS NULL OR activation_lease_until <= :now))
                   OR (activation_status = 'PROCESSING' AND activation_lease_until <= :now))
              AND activation_attempts < :maxAttempts
            ORDER BY next_activation_at ASC NULLS FIRST, created_at ASC, id ASC
            FOR UPDATE SKIP LOCKED LIMIT :limit
            """, nativeQuery = true)
    List<PaymentOrder> lockActivationCandidates(@Param("now") Instant now,
                                                  @Param("maxAttempts") int maxAttempts,
                                                  @Param("limit") int limit);

    @Query(value = """
            SELECT * FROM payment_orders
            WHERE status = 'SUCCEEDED' AND activation_attempts >= :maxAttempts
              AND ((activation_status IN ('PENDING','RETRY_REQUIRED')
                    AND (next_activation_at IS NULL OR next_activation_at <= :now)
                    AND (activation_lease_until IS NULL OR activation_lease_until <= :now))
                   OR (activation_status = 'PROCESSING' AND activation_lease_until <= :now))
            ORDER BY created_at ASC, id ASC FOR UPDATE SKIP LOCKED LIMIT :limit
            """, nativeQuery = true)
    List<PaymentOrder> lockExhaustedActivationCandidates(@Param("now") Instant now,
                                                          @Param("maxAttempts") int maxAttempts,
                                                          @Param("limit") int limit);
}
