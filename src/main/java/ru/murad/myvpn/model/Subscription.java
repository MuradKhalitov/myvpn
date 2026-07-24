package ru.murad.myvpn.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "subscriptions")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Subscription {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private TelegramUser user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tariff_id", nullable = false)
    private VpnTariff tariff;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SubscriptionStatus status;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "activated_by_telegram_id", nullable = false)
    private long activatedByTelegramId;

    @Column(name = "activated_at", nullable = false)
    private Instant activatedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "provisioning_owner", length = 64)
    private String provisioningOwner;

    @Column(name = "provisioning_lease_until")
    private Instant provisioningLeaseUntil;

    @Column(name = "provisioning_claim_token")
    private UUID provisioningClaimToken;

    @Column(name = "provisioning_attempt_count", nullable = false)
    private int provisioningAttemptCount;

    @Column(name = "next_provisioning_attempt_at")
    private Instant nextProvisioningAttemptAt;

    public void extend(VpnTariff tariff, long administratorTelegramId, Instant now) {
        this.tariff = tariff;
        this.expiresAt = expiresAt.plus(tariff.getDurationDays(), ChronoUnit.DAYS);
        this.activatedByTelegramId = administratorTelegramId;
        this.activatedAt = now;
        this.updatedAt = now;
    }

    public void activate(Instant now) {
        this.status = SubscriptionStatus.ACTIVE;
        this.updatedAt = now;
        clearProvisioningClaim();
    }

    public void fail(Instant now) {
        this.status = SubscriptionStatus.FAILED;
        this.updatedAt = now;
        clearProvisioningClaim();
    }

    public void requireReconciliation(Instant now, Instant nextAttemptAt) {
        this.status = SubscriptionStatus.RECONCILIATION_REQUIRED;
        this.updatedAt = now;
        this.nextProvisioningAttemptAt = nextAttemptAt;
        this.provisioningOwner = null;
        this.provisioningLeaseUntil = null;
    }

    public void claimProvisioning(
            String owner,
            UUID claimToken,
            Instant leaseUntil,
            Instant now
    ) {
        this.provisioningOwner = owner;
        this.provisioningClaimToken = claimToken;
        this.provisioningLeaseUntil = leaseUntil;
        this.provisioningAttemptCount++;
        this.updatedAt = now;
    }

    public void releaseProvisioningClaim(Instant nextAttemptAt, Instant now) {
        this.status = SubscriptionStatus.RECONCILIATION_REQUIRED;
        this.provisioningOwner = null;
        this.provisioningLeaseUntil = null;
        this.provisioningClaimToken = null;
        this.nextProvisioningAttemptAt = nextAttemptAt;
        this.updatedAt = now;
    }

    private void clearProvisioningClaim() {
        this.provisioningOwner = null;
        this.provisioningLeaseUntil = null;
        this.provisioningClaimToken = null;
        this.nextProvisioningAttemptAt = null;
    }

    public void requireManualReview(Instant now) {
        this.status = SubscriptionStatus.MANUAL_REVIEW_REQUIRED;
        this.updatedAt = now;
        clearProvisioningClaim();
    }

    public void markExpired(Instant now) {
        this.status = SubscriptionStatus.EXPIRED;
        this.updatedAt = now;
    }

    public void revoke(Instant now) {
        this.status = SubscriptionStatus.REVOKED;
        this.updatedAt = now;
    }
}
