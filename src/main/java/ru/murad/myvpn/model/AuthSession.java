package ru.murad.myvpn.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auth_sessions")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AuthSession {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "refresh_token_hash", nullable = false, length = 64)
    private String refreshTokenHash;

    @Column(name = "token_family_id", nullable = false)
    private UUID tokenFamilyId;

    @Column(name = "rotation_counter", nullable = false)
    private long rotationCounter;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    @Column(nullable = false)
    private long version;

    public boolean isUsableAt(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    public void rotate(String newRefreshTokenHash, Instant now) {
        refreshTokenHash = newRefreshTokenHash;
        rotationCounter++;
        lastUsedAt = now;
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }
}
