package ru.murad.myvpn.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.murad.myvpn.model.EmailOtpChallenge;

import java.util.Optional;
import java.util.UUID;

public interface EmailOtpChallengeRepository extends JpaRepository<EmailOtpChallenge, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailOtpChallenge>
    findFirstByNormalizedEmailAndConsumedAtIsNullAndInvalidatedAtIsNullOrderByCreatedAtDesc(
            String normalizedEmail);

    @Query(value = "select 1 from pg_advisory_xact_lock(hashtextextended(:normalizedEmail, 0))",
            nativeQuery = true)
    int acquireEmailLock(@Param("normalizedEmail") String normalizedEmail);
}
