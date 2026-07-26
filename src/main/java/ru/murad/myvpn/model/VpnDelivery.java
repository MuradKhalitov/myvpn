package ru.murad.myvpn.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** Durable, at-least-once delivery intent. It deliberately contains no VPN configuration. */
@Entity
@Table(name = "vpn_deliveries", uniqueConstraints = @UniqueConstraint(
        name = "uk_vpn_deliveries_source_type", columnNames = {"source_payment_order_id", "delivery_type"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VpnDelivery {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private TelegramUser user;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "subscription_id", nullable = false, updatable = false)
    private Subscription subscription;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "vpn_access_id", nullable = false, updatable = false)
    private VpnAccess vpnAccess;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "source_payment_order_id", nullable = false, updatable = false)
    private PaymentOrder sourcePaymentOrder;
    @Enumerated(EnumType.STRING) @Column(name = "delivery_type", nullable = false, length = 32)
    private VpnDeliveryType deliveryType;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
    private VpnDeliveryStatus status;
    @Column(nullable = false) private int attempts;
    @Column(nullable = false) private long generation;
    @Column(name = "claim_token") private UUID claimToken;
    @Column(name = "lease_until") private Instant leaseUntil;
    @Column(name = "next_attempt_at") private Instant nextAttemptAt;
    @Enumerated(EnumType.STRING) @Column(name = "safe_failure_code", length = 64)
    private VpnDeliveryFailureCode safeFailureCode;
    @Column(name = "subscription_version", nullable = false) private long subscriptionVersion;
    @Column(name = "subscription_expires_at", nullable = false) private Instant subscriptionExpiresAt;
    @Column(name = "vpn_access_version", nullable = false) private long vpnAccessVersion;
    @Column(name = "vpn_provider_name", nullable = false, length = 32) private String vpnProviderName;
    @Column(name = "vpn_external_access_id", nullable = false, length = 128) private String vpnExternalAccessId;
    @Column(name = "configuration_fingerprint", nullable = false, length = 64) private String configurationFingerprint;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "delivered_at") private Instant deliveredAt;
    @Column(name = "telegram_message_id") private Long telegramMessageId;
    @Version @Column(nullable = false) private long version;

    public static VpnDelivery automatic(TelegramUser user, Subscription subscription, VpnAccess access,
            PaymentOrder order, VpnDeliveryType type, String fingerprint, Instant now) {
        Objects.requireNonNull(user); Objects.requireNonNull(subscription); Objects.requireNonNull(access);
        Objects.requireNonNull(order); Objects.requireNonNull(type); requireFingerprint(fingerprint);
        requireRelationshipGraph(user, subscription, access, order);
        Instant timestamp = micros(now);
        VpnDelivery d = new VpnDelivery(); d.id = UUID.randomUUID(); d.user = user; d.subscription = subscription;
        d.vpnAccess = access; d.sourcePaymentOrder = order; d.deliveryType = type; d.status = VpnDeliveryStatus.PENDING;
        d.attempts = 0; d.generation = 0; d.configurationFingerprint = fingerprint;
        d.subscriptionVersion = requireVersion(subscription.getVersion()); d.subscriptionExpiresAt = micros(subscription.getExpiresAt());
        d.vpnAccessVersion = requireVersion(access.getVersion()); d.vpnProviderName = requireText(access.getProviderName());
        d.vpnExternalAccessId = requireText(access.getExternalAccessId());
        d.createdAt = timestamp; d.updatedAt = timestamp; return d;
    }
    public long claim(UUID token, Instant now, Duration leaseDuration, int maxAttempts) {
        Objects.requireNonNull(token); requireClaimable(now, maxAttempts);
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) throw new IllegalArgumentException("Delivery lease is invalid");
        generation = Math.incrementExact(generation); attempts = Math.incrementExact(attempts); claimToken = token;
        leaseUntil = micros(now.plus(leaseDuration)); status = VpnDeliveryStatus.PROCESSING; nextAttemptAt = null; deliveredAt = null; updatedAt = micros(now); return generation;
    }
    public long reclaim(UUID token, Instant now, Duration leaseDuration, int maxAttempts) {
        if (status != VpnDeliveryStatus.PROCESSING || leaseUntil == null || leaseUntil.isAfter(now)) throw new IllegalStateException("Delivery lease is active");
        return claim(token, now, leaseDuration, maxAttempts);
    }
    public void delivered(UUID token, long fencedGeneration, Long messageId, Instant now) {
        requireClaim(token, fencedGeneration); status = VpnDeliveryStatus.DELIVERED; deliveredAt = micros(now); telegramMessageId = messageId;
        claimToken = null; leaseUntil = null; nextAttemptAt = null; safeFailureCode = null; updatedAt = micros(now);
    }
    public void retry(UUID token, long fencedGeneration, VpnDeliveryFailureCode code, Instant now, Instant next) {
        requireClaim(token, fencedGeneration); if (next == null || next.isBefore(now)) throw new IllegalArgumentException("Delivery retry time is invalid");
        status = VpnDeliveryStatus.RETRY_REQUIRED; safeFailureCode = Objects.requireNonNull(code); nextAttemptAt = micros(next);
        claimToken = null; leaseUntil = null; deliveredAt = null; updatedAt = micros(now);
    }
    public void manualReview(UUID token, long fencedGeneration, VpnDeliveryFailureCode code, Instant now) {
        requireClaim(token, fencedGeneration); status = VpnDeliveryStatus.MANUAL_REVIEW_REQUIRED; safeFailureCode = Objects.requireNonNull(code);
        claimToken = null; leaseUntil = null; nextAttemptAt = null; deliveredAt = null; updatedAt = micros(now);
    }
    public void markExhausted(Instant now) {
        if (attempts < 0 || (status != VpnDeliveryStatus.PENDING && status != VpnDeliveryStatus.RETRY_REQUIRED
                && status != VpnDeliveryStatus.PROCESSING)
                || (status == VpnDeliveryStatus.PROCESSING && (leaseUntil == null || leaseUntil.isAfter(now)))) {
            throw new IllegalStateException("Delivery is not exhausted");
        }
        status = VpnDeliveryStatus.MANUAL_REVIEW_REQUIRED;
        safeFailureCode = VpnDeliveryFailureCode.MAX_ATTEMPTS_REACHED;
        claimToken = null; leaseUntil = null; nextAttemptAt = null; deliveredAt = null; updatedAt = micros(now);
    }
    private void requireClaimable(Instant now, int maxAttempts) {
        if (maxAttempts <= 0 || attempts >= maxAttempts || (status != VpnDeliveryStatus.PENDING && status != VpnDeliveryStatus.RETRY_REQUIRED && !(status == VpnDeliveryStatus.PROCESSING && leaseUntil != null && !leaseUntil.isAfter(now)))) throw new IllegalStateException("Delivery is not claimable");
    }
    private void requireClaim(UUID token, long fencedGeneration) {
        if (status != VpnDeliveryStatus.PROCESSING || !Objects.equals(claimToken, token) || generation != fencedGeneration) throw new IllegalStateException("Delivery claim is stale");
    }
    private static long requireVersion(Long version) { return version == null ? 0L : version; }
    private static void requireRelationshipGraph(TelegramUser user, Subscription subscription,
            VpnAccess access, PaymentOrder order) {
        if (!same(user.getId(), subscription.getUser().getId())
                || !same(subscription.getId(), access.getSubscription().getId())
                || !same(user.getId(), order.getUser().getId())
                || order.getSubscription() == null
                || !same(subscription.getId(), order.getSubscription().getId())) {
            throw new IllegalArgumentException("VPN delivery relationship graph is invalid");
        }
    }
    private static boolean same(UUID left, UUID right) { return left != null && left.equals(right); }
    private static String requireText(String value) { if (value == null || value.isBlank()) throw new IllegalArgumentException("Delivery snapshot is invalid"); return value; }
    private static void requireFingerprint(String fingerprint) { if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Configuration fingerprint is invalid"); }
    private static Instant micros(Instant value) { return Objects.requireNonNull(value).truncatedTo(ChronoUnit.MICROS); }
    @Override public String toString() { return "VpnDelivery[status=" + status + ", type=" + deliveryType + ", attempts=" + attempts + "]"; }
}
