package ru.murad.myvpn.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Builder.Default;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vpn_accesses")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class VpnAccess {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @OneToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "subscription_id", unique = true)
    private Subscription subscription;

    @Column(name = "provider_name", nullable = false, length = 32)
    private String providerName;

    @Column(name = "external_access_id", nullable = false, unique = true, length = 128)
    private String externalAccessId;

    @Column(name = "provider_client_key", length = 96)
    private String providerClientKey;

    @Column(name = "configuration_data", columnDefinition = "text")
    private String configurationData;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VpnAccessStatus status;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "configuration_deleted_at")
    private Instant configurationDeletedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "desired_entitlement", nullable = false, length = 16)
    @Default
    private VpnEntitlement desiredEntitlement = VpnEntitlement.FREE;

    @Enumerated(EnumType.STRING)
    @Column(name = "applied_entitlement", length = 16)
    private VpnEntitlement appliedEntitlement;

    @Column(name = "policy_generation", nullable = false)
    @Default
    private long policyGeneration = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "policy_status", nullable = false, length = 24)
    @Default
    private VpnPolicyStatus policyStatus = VpnPolicyStatus.RETRY_REQUIRED;

    @Column(name = "next_policy_attempt_at")
    private Instant nextPolicyAttemptAt;

    @Column(name = "quota_period_started_at")
    private Instant quotaPeriodStartedAt;

    @Column(name = "quota_period_ends_at")
    private Instant quotaPeriodEndsAt;

    @Version
    @Column(nullable = false)
    private Long version;

    public void revoke(Instant now) {
        this.status = VpnAccessStatus.REVOKED;
        this.revokedAt = now;
        this.updatedAt = now;
    }

    public void deleteConfiguration(Instant now) {
        this.configurationData = null;
        this.configurationDeletedAt = now;
        this.updatedAt = now;
    }

    public void requestPolicy(VpnEntitlement entitlement, Instant quotaStartsAt,
            Instant quotaEndsAt, Instant now) {
        if (desiredEntitlement != entitlement) {
            policyGeneration++;
        }
        desiredEntitlement = entitlement;
        quotaPeriodStartedAt = quotaStartsAt;
        quotaPeriodEndsAt = quotaEndsAt;
        policyStatus = VpnPolicyStatus.PENDING;
        nextPolicyAttemptAt = now;
        updatedAt = now;
    }

    public boolean applyPolicy(long generation, Instant now) {
        if (generation != policyGeneration) return false;
        appliedEntitlement = desiredEntitlement;
        policyStatus = VpnPolicyStatus.APPLIED;
        nextPolicyAttemptAt = null;
        updatedAt = now;
        return true;
    }

    public boolean retryPolicy(long generation, Instant nextAttemptAt, Instant now) {
        if (generation != policyGeneration) return false;
        policyStatus = VpnPolicyStatus.RETRY_REQUIRED;
        nextPolicyAttemptAt = nextAttemptAt;
        updatedAt = now;
        return true;
    }

    public void completeProvisioning(String providerName, String configurationData,
            Instant now) {
        this.providerName = providerName;
        this.configurationData = configurationData;
        this.status = VpnAccessStatus.ACTIVE;
        this.issuedAt = now;
        this.updatedAt = now;
    }

    public void attachSubscription(Subscription subscription, Instant now) {
        this.subscription = subscription;
        this.updatedAt = now;
    }
}
