package ru.murad.myvpn.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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
@Table(name = "email_otp_challenges")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class EmailOtpChallenge {

    @Id
    private UUID id;

    @Column(name = "normalized_email", nullable = false, length = 320)
    private String normalizedEmail;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "resend_after", nullable = false)
    private Instant resendAfter;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "invalidated_at")
    private Instant invalidatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    @Column(nullable = false)
    private long version;

    public boolean isActiveAt(Instant now) {
        return consumedAt == null && invalidatedAt == null
                && now.isBefore(expiresAt) && attempts < maxAttempts;
    }

    public void recordFailure(Instant now) {
        attempts++;
        if (attempts >= maxAttempts) {
            invalidatedAt = now;
        }
    }

    public void consume(Instant now) {
        consumedAt = now;
    }

    public void invalidate(Instant now) {
        if (consumedAt == null && invalidatedAt == null) {
            invalidatedAt = now;
        }
    }
}
