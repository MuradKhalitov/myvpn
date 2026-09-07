package ru.murad.myvpn.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.murad.myvpn.model.AuthSession;

import java.util.Optional;
import java.util.UUID;

public interface AuthSessionRepository extends JpaRepository<AuthSession, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from AuthSession session where session.refreshTokenHash = :refreshTokenHash")
    Optional<AuthSession> findByRefreshTokenHashForUpdate(
            @Param("refreshTokenHash") String refreshTokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from AuthSession session where session.previousRefreshTokenHash = :refreshTokenHash")
    Optional<AuthSession> findByPreviousRefreshTokenHashForUpdate(
            @Param("refreshTokenHash") String refreshTokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select session from AuthSession session where session.id = :id")
    Optional<AuthSession> findByIdForUpdate(@Param("id") UUID id);
}
