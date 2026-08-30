package ru.murad.myvpn.repository;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
