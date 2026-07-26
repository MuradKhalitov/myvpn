package ru.murad.myvpn.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.murad.myvpn.config.VpnDeliveryProperties;
import ru.murad.myvpn.model.*;
import ru.murad.myvpn.repository.VpnDeliveryRepository;
import ru.murad.myvpn.repository.SubscriptionRepository;
import ru.murad.myvpn.repository.VpnAccessRepository;
import ru.murad.myvpn.service.ClaimedVpnDelivery;
import ru.murad.myvpn.service.VpnDeliveryTransactionService;
import java.time.Instant;
import java.util.*;

@Service @RequiredArgsConstructor
public class VpnDeliveryTransactionServiceImpl implements VpnDeliveryTransactionService {
    private final VpnDeliveryRepository deliveries; private final SubscriptionRepository subscriptions;
    private final VpnAccessRepository accesses; private final VpnDeliveryProperties properties;
    @Override @Transactional public int markExhaustedDeliveries(Instant now, int limit) {
        if (limit <= 0 || limit > 100) throw new IllegalArgumentException("Delivery batch limit is invalid");
        List<VpnDelivery> exhausted = deliveries.lockExhaustedCandidates(now, properties.maxAttempts(), limit);
        exhausted.forEach(delivery -> delivery.markExhausted(now));
        return exhausted.size();
    }
    @Override @Transactional public List<ClaimedVpnDelivery> claim(Instant now, int limit) {
        List<ClaimedVpnDelivery> result = new ArrayList<>();
        for (VpnDelivery delivery : deliveries.lockCandidates(now, properties.maxAttempts(), limit)) {
            UUID token = UUID.randomUUID(); long generation = delivery.getStatus() == VpnDeliveryStatus.PROCESSING
                    ? delivery.reclaim(token, now, properties.leaseDuration(), properties.maxAttempts())
                    : delivery.claim(token, now, properties.leaseDuration(), properties.maxAttempts());
            if (!relationshipGraphValid(delivery)) {
                delivery.manualReview(token, generation, VpnDeliveryFailureCode.RELATIONSHIP_MISMATCH, now);
                continue;
            }
            result.add(new ClaimedVpnDelivery(delivery.getId(), delivery.getUser().getId(), delivery.getUser().getTelegramId(),
                    delivery.getSubscription().getId(), delivery.getVpnAccess().getId(), delivery.getSourcePaymentOrder().getId(), token, generation,
                    delivery.getSubscriptionVersion(), delivery.getVpnAccessVersion(), delivery.getSubscriptionExpiresAt(),
                    delivery.getVpnProviderName(), delivery.getVpnExternalAccessId(), delivery.getConfigurationFingerprint(),
                    delivery.getDeliveryType(), delivery.getSubscription().getExpiresAt(), delivery.getSubscription().getTariff().getName()));
        } return List.copyOf(result);
    }
    @Override @Transactional public boolean delivered(ClaimedVpnDelivery claim, Long messageId, Instant now) {
        return fenced(claim).map(d -> { if (snapshotValid(d, claim)) d.delivered(claim.token(), claim.generation(), messageId, now);
            else d.manualReview(claim.token(), claim.generation(), VpnDeliveryFailureCode.SNAPSHOT_INVALID, now); return true; }).orElse(false);
    }
    @Override @Transactional public boolean retry(ClaimedVpnDelivery claim, VpnDeliveryFailureCode code, Instant now, Instant next) {
        return fenced(claim).map(d -> { if (!snapshotValid(d, claim)) d.manualReview(claim.token(), claim.generation(), VpnDeliveryFailureCode.SNAPSHOT_INVALID, now);
            else if (d.getAttempts() >= properties.maxAttempts()) d.manualReview(claim.token(), claim.generation(), VpnDeliveryFailureCode.MAX_ATTEMPTS_REACHED, now);
            else d.retry(claim.token(), claim.generation(), code, now, next);
            return true; }).orElse(false);
    }
    @Override @Transactional public boolean manualReview(ClaimedVpnDelivery claim, VpnDeliveryFailureCode code, Instant now) {
        return fenced(claim).map(d -> { d.manualReview(claim.token(), claim.generation(), code, now); return true; }).orElse(false);
    }
    private Optional<VpnDelivery> fenced(ClaimedVpnDelivery claim) {
        return deliveries.findById(claim.deliveryId()).filter(d -> d.getStatus() == VpnDeliveryStatus.PROCESSING
                && Objects.equals(d.getClaimToken(), claim.token()) && d.getGeneration() == claim.generation());
    }
    private boolean snapshotValid(VpnDelivery delivery, ClaimedVpnDelivery claim) {
        Subscription subscription = subscriptions.findById(delivery.getSubscription().getId()).orElse(null);
        VpnAccess access = accesses.findById(delivery.getVpnAccess().getId()).orElse(null);
        return relationshipGraphValid(delivery) && delivery.getSourcePaymentOrder().getId().equals(claim.sourcePaymentOrderId())
                && subscription != null && access != null && subscription.getStatus() == SubscriptionStatus.ACTIVE
                && access.getStatus() == VpnAccessStatus.ACTIVE && subscription.getUser().getId().equals(delivery.getUser().getId())
                && version(subscription.getVersion()) == delivery.getSubscriptionVersion()
                && Objects.equals(subscription.getExpiresAt(), delivery.getSubscriptionExpiresAt())
                && version(access.getVersion()) == delivery.getVpnAccessVersion()
                && Objects.equals(access.getProviderName(), delivery.getVpnProviderName())
                && Objects.equals(access.getExternalAccessId(), delivery.getVpnExternalAccessId())
                && fingerprint(access.getConfigurationData()).equals(delivery.getConfigurationFingerprint());
    }
    private boolean relationshipGraphValid(VpnDelivery delivery) {
        PaymentOrder order = delivery.getSourcePaymentOrder();
        Subscription subscription = delivery.getSubscription();
        VpnAccess access = delivery.getVpnAccess();
        return order != null && subscription != null && access != null
                && same(delivery.getUser().getId(), subscription.getUser().getId())
                && same(subscription.getId(), access.getSubscription().getId())
                && same(delivery.getUser().getId(), order.getUser().getId())
                && order.getSubscription() != null && same(subscription.getId(), order.getSubscription().getId());
    }
    private boolean same(UUID left, UUID right) { return left != null && left.equals(right); }
    private long version(Long version) { return version == null ? 0L : version; }
    private String fingerprint(String configuration) {
        if (configuration == null || configuration.isBlank()) return "";
        try { byte[] bytes = java.security.MessageDigest.getInstance("SHA-256").digest(configuration.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(64); for (byte b : bytes) value.append(String.format("%02x", b)); return value.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
}
