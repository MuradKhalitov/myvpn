package ru.murad.myvpn.service.impl;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.murad.myvpn.client.ProvisionedVpnAccess;
import ru.murad.myvpn.client.VpnProvider;
import ru.murad.myvpn.config.PaymentProperties;
import ru.murad.myvpn.exception.PaymentNotFoundException;
import ru.murad.myvpn.exception.PaymentOrderValidationException;
import ru.murad.myvpn.model.*;
import ru.murad.myvpn.repository.PaymentOrderRepository;
import ru.murad.myvpn.repository.SubscriptionRepository;
import ru.murad.myvpn.repository.VpnAccessRepository;
import ru.murad.myvpn.repository.VpnDeliveryRepository;
import ru.murad.myvpn.repository.VpnTariffRepository;
import ru.murad.myvpn.service.*;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
@RequiredArgsConstructor
public class PaymentActivationTransactionServiceImpl implements PaymentActivationTransactionService {
    private final PaymentOrderRepository orders;
    private final SubscriptionRepository subscriptions;
    private final VpnAccessRepository accesses;
    private final VpnTariffRepository tariffs;
    private final VpnDeliveryRepository deliveries;
    private final EntityManager entityManager;
    private final PaymentProperties properties;
    private final VpnProvider vpnProvider;

    @Override @Transactional
    public List<PreparedPaymentActivation> claimActivations(Instant now, int limit) {
        if (now == null || limit <= 0 || limit > 100) throw new PaymentOrderValidationException("Invalid activation claim parameters");
        List<PreparedPaymentActivation> result = new ArrayList<>();
        for (PaymentOrder order : orders.lockActivationCandidates(now.truncatedTo(ChronoUnit.MICROS), properties.activation().maxAttempts(), limit)) {
            UUID token = UUID.randomUUID();
            Instant lease = plus(now, properties.activation().leaseDuration(), "Activation lease");
            long generation;
            if (order.getActivationStatus() == PaymentActivationStatus.PROCESSING) {
                generation = order.reclaimExpiredActivation(now, properties.activation().leaseDuration(), token, properties.activation().maxAttempts());
            } else {
                generation = order.claimActivation(token, lease, now, properties.activation().maxAttempts());
            }
            Subscription active;
            List<Subscription> allActive = subscriptions.findAllByUserIdAndStatus(order.getUser().getId(), SubscriptionStatus.ACTIVE);
            if (allActive.size() > 1) {
                order.markActivationManualReviewRequired(token, generation, now);
                order.setSafeFailureCode("ACTIVATION_MULTIPLE_ACTIVE_SUBSCRIPTIONS");
                continue;
            }
            active = order.getSubscription() != null
                    ? subscriptions.findById(order.getSubscription().getId()).orElse(null)
                    : (allActive.isEmpty() ? null : allActive.get(0));
            if (order.getSubscription() != null && active == null) {
                order.markActivationManualReviewRequired(token, generation, now);
                order.setSafeFailureCode("ACTIVATION_LINKED_SUBSCRIPTION_NOT_FOUND");
                continue;
            }
            VpnAccess access = active == null ? null : accesses.findBySubscriptionId(active.getId()).orElse(null);
            if (active != null && (access == null || active.getStatus() != SubscriptionStatus.ACTIVE
                    || access.getStatus() != VpnAccessStatus.ACTIVE)) {
                order.markActivationManualReviewRequired(token, generation, now);
                order.setSafeFailureCode("ACTIVATION_SNAPSHOT_INVALID");
                continue;
            }
            if (active != null) {
                active = subscriptions.findByIdForUpdate(active.getId()).orElseThrow(PaymentNotFoundException::new);
                access = accesses.findByIdForUpdate(access.getId()).orElse(null);
                if (access == null || active.getStatus() != SubscriptionStatus.ACTIVE
                        || access.getStatus() != VpnAccessStatus.ACTIVE) {
                    order.markActivationManualReviewRequired(token, generation, now);
                    order.setSafeFailureCode("ACTIVATION_SNAPSHOT_INVALID");
                    continue;
                }
                order.attachSubscription(active, now);
            }
            PaymentActivationAction action = active == null ? PaymentActivationAction.PROVISION : PaymentActivationAction.EXTEND;
            Instant target = order.getActivationTargetExpiresAt();
            if (target == null) {
                Instant base = active == null || active.getExpiresAt().isBefore(now) ? now : active.getExpiresAt();
                target = plus(base, java.time.Duration.ofDays(order.getDurationDaysSnapshot()), "Activation target");
                order.fixActivationTargetExpiresAt(token, generation, target, now);
            }
            String external = active == null ? order.getUser().getId().toString() : access.getExternalAccessId();
            result.add(new PreparedPaymentActivation(order.getId(), order.getUser().getId(), order.getProvider(), action,
                    generation, token, order.getDurationDaysSnapshot(), target,
                    active == null ? null : active.getId(), active == null ? null : access.getId(), external,
                    active == null ? null : active.getVersion(), active == null ? null : active.getExpiresAt(),
                    order.getStatus(), order.getActivationStatus(), order.getTariff().getId(),
                    order.getTariffCodeSnapshot(), order.getTariffNameSnapshot(), vpnProvider.providerName(),
                    active == null ? null : access.getVersion(), active == null ? null : access.getStatus(),
                    active == null ? null : access.getProviderName()));
        }
        return List.copyOf(result);
    }

    @Override @Transactional
    public int markExhaustedActivations(Instant now, int limit) {
        int marked = 0;
        for (PaymentOrder order : orders.lockExhaustedActivationCandidates(now, properties.activation().maxAttempts(), limit)) {
            order.markAttemptsExhausted(now);
            marked++;
        }
        return marked;
    }

    @Override @Transactional
    public PaymentActivationOutcome complete(PreparedPaymentActivation p, ProvisionedVpnAccess result, Instant now) {
        PaymentOrder order = orders.findByIdForUpdate(p.paymentOrderId()).orElseThrow(PaymentNotFoundException::new);
        if (order.getActivationStatus() == PaymentActivationStatus.ACTIVATED) return PaymentActivationOutcome.ALREADY_ACTIVATED;
        if (!matches(order, p) || order.getStatus() != PaymentStatus.SUCCEEDED) return PaymentActivationOutcome.STALE;
        validateResult(p, result);
        VpnTariff tariff = tariffs.findById(order.getTariff().getId()).orElse(order.getTariff());
        Subscription subscription;
        if (p.action() == PaymentActivationAction.PROVISION) {
            if (order.getSubscription() != null) return PaymentActivationOutcome.ALREADY_ACTIVATED;
            subscription = Subscription.builder().id(UUID.randomUUID()).user(order.getUser()).tariff(tariff)
                    .status(SubscriptionStatus.ACTIVE).startsAt(now).expiresAt(p.targetExpiresAt())
                    .activatedByTelegramId(order.getUser().getTelegramId()).activatedAt(now).createdAt(now).updatedAt(now).build();
            subscriptions.save(subscription);
            VpnAccess access = VpnAccess.builder().id(UUID.randomUUID()).subscription(subscription)
                    .providerName(result.providerName()).externalAccessId(result.externalAccessId())
                    .configurationData(result.configurationData())
                    .status(VpnAccessStatus.ACTIVE).issuedAt(now).createdAt(now).updatedAt(now).build();
            accesses.save(access);
            order.attachSubscription(subscription, now);
        } else {
            subscription = subscriptions.findByIdForUpdate(p.existingSubscriptionId()).orElseThrow(PaymentNotFoundException::new);
            VpnAccess access = accesses.findByIdForUpdate(p.existingVpnAccessId()).orElse(null);
            if (access == null) return PaymentActivationOutcome.STALE;
            if (!subscription.getUser().getId().equals(order.getUser().getId()) || !access.getId().equals(p.existingVpnAccessId())
                    || !access.getExternalAccessId().equals(p.stableExternalClientId()) || subscription.getStatus() != SubscriptionStatus.ACTIVE
                    || access.getStatus() != VpnAccessStatus.ACTIVE || !Objects.equals(access.getVersion(), p.existingVpnAccessVersion())
                    || !Objects.equals(access.getProviderName(), p.existingVpnProviderName())
                    || !result.externalAccessId().equals(access.getExternalAccessId())
                    || !Objects.equals(subscription.getVersion(), p.existingSubscriptionVersion())
                    || !subscription.getExpiresAt().equals(p.existingSubscriptionExpiresAt())) return PaymentActivationOutcome.STALE;
            subscription.setActivationTarget(tariff, order.getUser().getTelegramId(), p.targetExpiresAt(), now);
            subscriptions.save(subscription);
        }
        VpnAccess deliveryAccess = p.action() == PaymentActivationAction.PROVISION
                ? accesses.findBySubscriptionId(subscription.getId()).orElseThrow(PaymentNotFoundException::new)
                : accesses.findByIdForUpdate(p.existingVpnAccessId()).orElseThrow(PaymentNotFoundException::new);
        if (deliveryAccess.getConfigurationData() == null || deliveryAccess.getConfigurationData().isBlank()) {
            throw new PaymentOrderValidationException("VPN configuration is unavailable for delivery");
        }
        validateDeliveryRelationshipGraph(order, subscription, deliveryAccess);
        // The outbox snapshots optimistic-lock versions. Flush the preceding subscription/access
        // mutation first so Hibernate has populated their persisted @Version values.
        entityManager.flush();
        VpnDeliveryType deliveryType = p.action() == PaymentActivationAction.PROVISION
                ? VpnDeliveryType.ACTIVATION_PROVISION : VpnDeliveryType.ACTIVATION_EXTEND;
        deliveries.save(VpnDelivery.automatic(order.getUser(), subscription, deliveryAccess, order,
                deliveryType, configurationFingerprint(deliveryAccess.getConfigurationData()), now));
        order.markActivated(p.token(), p.generation(), now);
        orders.save(order);
        return PaymentActivationOutcome.SUCCEEDED;
    }

    @Override @Transactional
    public PaymentActivationOutcome retry(PreparedPaymentActivation p, String code, Instant now) {
        PaymentOrder order = orders.findByIdForUpdate(p.paymentOrderId()).orElseThrow(PaymentNotFoundException::new);
        if (!matches(order, p)) return PaymentActivationOutcome.STALE;
        order.scheduleRetry(p.token(), p.generation(), now, plus(now, properties.activation().retryDelay(), "Activation retry"));
        order.setSafeFailureCode(code);
        return PaymentActivationOutcome.RETRY_SCHEDULED;
    }

    @Override @Transactional
    public PaymentActivationOutcome manualReview(PreparedPaymentActivation p, String code, Instant now) {
        PaymentOrder order = orders.findByIdForUpdate(p.paymentOrderId()).orElseThrow(PaymentNotFoundException::new);
        if (!matches(order, p)) return PaymentActivationOutcome.STALE;
        order.markActivationManualReviewRequired(p.token(), p.generation(), now);
        order.setSafeFailureCode(code);
        return PaymentActivationOutcome.MANUAL_REVIEW_REQUIRED;
    }

    private boolean matches(PaymentOrder o, PreparedPaymentActivation p) {
        return o.getId().equals(p.paymentOrderId()) && o.getUser().getId().equals(p.userId())
                && o.getStatus() == p.paymentStatus() && o.getProvider() == p.provider()
                && o.getActivationStatus() == p.activationStatus() && o.getActivationGeneration() == p.generation()
                && Objects.equals(o.getActivationClaimToken(), p.token()) && o.getActivationStatus() == PaymentActivationStatus.PROCESSING
                && Objects.equals(o.getActivationTargetExpiresAt(), p.targetExpiresAt())
                && Objects.equals(o.getDurationDaysSnapshot(), p.durationDays())
                && Objects.equals(o.getTariff().getId(), p.tariffId())
                && Objects.equals(o.getTariffCodeSnapshot(), p.tariffCodeSnapshot())
                && Objects.equals(o.getTariffNameSnapshot(), p.tariffNameSnapshot())
                && Objects.equals(o.getSubscription() == null ? null : o.getSubscription().getId(), p.existingSubscriptionId());
    }
    private void validateResult(PreparedPaymentActivation p, ProvisionedVpnAccess r) {
        if (r == null || r.externalAccessId() == null || r.externalAccessId().isBlank() || r.externalAccessId().length() > 128
                || r.targetExpiresAt() == null) throw new PaymentOrderValidationException("Invalid VPN provider result");
        if (!Objects.equals(r.externalAccessId(), p.stableExternalClientId())
                || !Objects.equals(r.providerName(), p.vpnProviderName())
                || !r.targetExpiresAt().equals(p.targetExpiresAt())) {
            throw new ru.murad.myvpn.exception.PaymentActivationResultMismatchException("VPN provider result does not match activation snapshot");
        }
        if (p.action() == PaymentActivationAction.PROVISION && (r.configurationData() == null || r.configurationData().isBlank())) throw new PaymentOrderValidationException("Incomplete VPN provision result");
    }

    private String configurationFingerprint(String configuration) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(configuration.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
    private void validateDeliveryRelationshipGraph(PaymentOrder order, Subscription subscription, VpnAccess access) {
        if (!same(order.getUser().getId(), subscription.getUser().getId())
                || !same(subscription.getId(), access.getSubscription().getId())
                || order.getSubscription() == null
                || !same(subscription.getId(), order.getSubscription().getId())) {
            throw new PaymentOrderValidationException("VPN delivery relationship graph is invalid");
        }
    }
    private boolean same(UUID left, UUID right) { return left != null && left.equals(right); }
    private Instant plus(Instant base, java.time.Duration amount, String message) { try { return base.plus(amount).truncatedTo(ChronoUnit.MICROS); } catch (DateTimeException | ArithmeticException e) { throw new PaymentOrderValidationException(message + " cannot be applied"); } }
}
