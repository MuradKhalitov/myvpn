package ru.murad.myvpn.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.murad.myvpn.model.VpnAccess;
import ru.murad.myvpn.model.VpnAccessStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;

public interface VpnAccessRepository extends JpaRepository<VpnAccess, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select access from VpnAccess access where access.id = :id")
    Optional<VpnAccess> findByIdForUpdate(@Param("id") UUID id);

    @EntityGraph(attributePaths = "subscription")
    @Query("select access from VpnAccess access where access.id = :id")
    Optional<VpnAccess> findByIdForDelivery(@Param("id") UUID id);

    Optional<VpnAccess> findBySubscriptionId(UUID subscriptionId);

    List<VpnAccess> findAllByStatusAndRevokedAtLessThanEqualAndConfigurationDataIsNotNull(
            VpnAccessStatus status,
            Instant threshold
    );
}
