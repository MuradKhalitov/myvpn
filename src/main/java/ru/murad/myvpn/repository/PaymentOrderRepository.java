package ru.murad.myvpn.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, UUID> {

    Optional<PaymentOrder> findByProviderAndProviderPaymentId(
            PaymentProviderType provider,
            String providerPaymentId
    );

    Optional<PaymentOrder> findByIdempotenceKey(UUID idempotenceKey);

    Optional<PaymentOrder> findFirstByUserIdAndStatusIn(
            UUID userId,
            Collection<PaymentStatus> statuses
    );

    default Optional<PaymentOrder> findOpenByUser(UUID userId) {
        return findFirstByUserIdAndStatusIn(
                userId,
                EnumSet.of(
                        PaymentStatus.NEW,
                        PaymentStatus.CREATING,
                        PaymentStatus.PENDING,
                        PaymentStatus.MANUAL_REVIEW_REQUIRED));
    }

    @Query("select p from PaymentOrder p where p.user.id = :userId order by p.createdAt desc")
    List<PaymentOrder> findAllByUserOrderByCreatedAtDesc(@Param("userId") UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select paymentOrder from PaymentOrder paymentOrder where paymentOrder.id = :id")
    Optional<PaymentOrder> findByIdForUpdate(@Param("id") UUID id);
}
