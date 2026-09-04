package ru.murad.myvpn.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.murad.myvpn.model.PhoneVerification;

import java.util.Optional;
import java.time.Instant;
import java.util.UUID;

public interface PhoneVerificationRepository extends JpaRepository<PhoneVerification, UUID> {
    Optional<PhoneVerification> findFirstByPhoneAndStatusOrderByCreatedAtDesc(String phone, ru.murad.myvpn.model.PhoneVerificationStatus status);
    Optional<PhoneVerification> findFirstByPhoneOrderByCreatedAtDesc(String phone);
    long countByPhoneAndCreatedAtAfter(String phone, Instant after);
    long countByRequestIpAndCreatedAtAfter(String requestIp, Instant after);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select verification from PhoneVerification verification where verification.id = :id")
    Optional<PhoneVerification> findByIdForUpdate(@Param("id") UUID id);

    @Query(value = "select 1 from pg_advisory_xact_lock(hashtextextended(:phone, 0))", nativeQuery = true)
    int lockPhone(@Param("phone") String phone);
}
