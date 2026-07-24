package ru.murad.myvpn.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.murad.myvpn.model.VpnAccess;

import java.util.Optional;
import java.util.UUID;

public interface VpnAccessRepository extends JpaRepository<VpnAccess, UUID> {

    Optional<VpnAccess> findBySubscriptionId(UUID subscriptionId);
}
