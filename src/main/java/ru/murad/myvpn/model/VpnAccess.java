package ru.murad.myvpn.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
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
@Table(name = "vpn_accesses")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class VpnAccess {

    @Id
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subscription_id", nullable = false, unique = true)
    private Subscription subscription;

    @Column(name = "provider_name", nullable = false, length = 32)
    private String providerName;

    @Column(name = "external_access_id", nullable = false, unique = true, length = 128)
    private String externalAccessId;

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
}
