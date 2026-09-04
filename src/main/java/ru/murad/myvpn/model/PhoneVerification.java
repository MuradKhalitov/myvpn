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
@Table(name = "phone_verifications")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class PhoneVerification {
    @Id private UUID id;
    @Column(name = "phone", nullable = false, length = 16) private String phone;
    @Column(name = "request_ip", length = 64) private String requestIp;
    @Column(name = "provider", nullable = false, length = 32) private String provider;
    @Column(name = "provider_check_id", nullable = false, unique = true, length = 128) private String providerCheckId;
    @Column(name = "call_phone", length = 32) private String callPhone;
    @Column(name = "call_phone_pretty", length = 64) private String callPhonePretty;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private PhoneVerificationStatus status;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "verified_at") private Instant verifiedAt;
    @Column(name = "account_id") private UUID accountId;
    @Column(name = "exchange_token_hash", length = 128) private String exchangeTokenHash;
    @Column(name = "exchange_expires_at") private Instant exchangeExpiresAt;
    @Column(name = "exchange_consumed_at") private Instant exchangeConsumedAt;
    @Version @Column(nullable = false) private long version;

    public boolean activeAt(Instant now) { return status == PhoneVerificationStatus.PENDING && expiresAt.isAfter(now); }
    public void expire(Instant now) { if (status == PhoneVerificationStatus.PENDING) status = PhoneVerificationStatus.EXPIRED; }
    public void verify(UUID accountId, String tokenHash, Instant exchangeExpiresAt, Instant now) {
        if (status != PhoneVerificationStatus.PENDING) return;
        status = PhoneVerificationStatus.VERIFIED; this.accountId = accountId; this.verifiedAt = now;
        this.exchangeTokenHash = tokenHash; this.exchangeExpiresAt = exchangeExpiresAt;
    }
    public boolean consumeExchange(String tokenHash, Instant now) {
        if (status != PhoneVerificationStatus.VERIFIED || exchangeConsumedAt != null || exchangeExpiresAt == null
                || !exchangeExpiresAt.isAfter(now) || !java.util.Objects.equals(exchangeTokenHash, tokenHash)) return false;
        exchangeConsumedAt = now; return true;
    }
}
