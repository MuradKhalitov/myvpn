package ru.murad.myvpn.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "accounts")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Account {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountStatus status;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "trial_started_at")
    private Instant trialStartedAt;

    @Column(name = "trial_expires_at")
    private Instant trialExpiresAt;

    @Column(name = "trial_granted_at")
    private Instant trialGrantedAt;

    public boolean hasActiveTrialAt(Instant now) {
        return trialExpiresAt != null && trialExpiresAt.isAfter(now);
    }

    public void grantTrial(Instant now, Instant expiresAt) {
        if (trialGrantedAt != null) return;
        this.trialStartedAt = now;
        this.trialExpiresAt = expiresAt;
        this.trialGrantedAt = now;
        this.updatedAt = now;
    }
}
