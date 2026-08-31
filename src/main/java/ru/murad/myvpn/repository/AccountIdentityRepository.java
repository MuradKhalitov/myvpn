package ru.murad.myvpn.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.murad.myvpn.model.AccountIdentity;
import ru.murad.myvpn.model.AccountIdentityType;

import java.util.Optional;
import java.util.UUID;

public interface AccountIdentityRepository extends JpaRepository<AccountIdentity, UUID> {

    Optional<AccountIdentity> findByTypeAndNormalizedSubject(
            AccountIdentityType type,
            String normalizedSubject
    );

    boolean existsByTypeAndNormalizedSubject(
            AccountIdentityType type,
            String normalizedSubject
    );

    /**
     * Serializes first registration for one public install id. The lock is
     * transaction-scoped, so it is released when the registration transaction
     * commits or rolls back.
     */
    @Query(value = "select 1 from pg_advisory_xact_lock(hashtextextended(:installId, 0))", nativeQuery = true)
    int lockDeviceRegistration(@Param("installId") String installId);
}
