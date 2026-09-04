package ru.murad.myvpn.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.murad.myvpn.model.Account;

import java.util.UUID;
import java.time.Instant;
import java.util.List;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    List<Account> findAllByTrialGrantedAtIsNotNullAndTrialExpiresAtLessThanEqual(Instant now);
}
