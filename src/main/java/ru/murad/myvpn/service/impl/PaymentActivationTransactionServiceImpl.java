package ru.murad.myvpn.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.murad.myvpn.client.ProvisionedVpnAccess;
import ru.murad.myvpn.client.VpnProvider;
import ru.murad.myvpn.config.PaymentProperties;
import ru.murad.myvpn.exception.PaymentNotFoundException;
import ru.murad.myvpn.exception.PaymentActivationResultMismatchException;
import ru.murad.myvpn.exception.PaymentOrderValidationException;
import ru.murad.myvpn.model.*;
import ru.murad.myvpn.repository.PaymentOrderRepository;
import ru.murad.myvpn.repository.SubscriptionRepository;
import ru.murad.myvpn.repository.VpnAccessRepository;
import ru.murad.myvpn.repository.VpnTariffRepository;
import ru.murad.myvpn.service.*;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentActivationTransactionServiceImpl implements PaymentActivationTransactionService {
    private static final Duration PROVIDER_EXPIRY_TOLERANCE = Duration.ofSeconds(1);
    private final PaymentOrderRepository orders;
    private final SubscriptionRepository subscriptions;
    private final VpnAccessRepository accesses;
    private final VpnTariffRepository tariffs;
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
            List<Subscription> allActive = subscriptions.findAllByAccountIdAndStatus(order.getAccount().getId(), SubscriptionStatus.ACTIVE);
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
            VpnAccess access = active == null
                    ? accesses.findByAccountId(order.getAccount().getId()).orElse(null)
                    : accesses.findBySubscriptionId(active.getId()).orElse(null);
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
            PaymentActivationAction action = active != null ? PaymentActivationAction.EXTEND
                    : access != null && access.getStatus() == VpnAccessStatus.ACTIVE
                            ? PaymentActivationAction.ACTIVATE_EXISTING : PaymentActivationAction.PROVISION;
            Instant target = order.getActivationTargetExpiresAt();
            if (target == null) {
                Instant base = active != null && !active.getExpiresAt().isBefore(now) ? active.getExpiresAt()
                        : order.getAccount().hasActiveTrialAt(now) ? order.getAccount().getTrialExpiresAt() : now;
                target = plus(base, java.time.Duration.ofDays(order.getDurationDaysSnapshot()), "Activation target");
                order.fixActivationTargetExpiresAt(token, generation, target, now);
            }
            String external = action == PaymentActivationAction.PROVISION
                    ? order.getAccount().getId().toString() : access.getExternalAccessId();
            result.add(new PreparedPaymentActivation(order.getId(), order.getAccount().getId(), order.getProvider(), action,
                    generation, token, order.getDurationDaysSnapshot(), target,
                    active == null ? null : active.getId(), action == PaymentActivationAction.PROVISION ? null : access.getId(), external,
                    active == null ? null : active.getVersion(), active == null ? null : active.getExpiresAt(),
                    order.getStatus(), order.getActivationStatus(), order.getTariff().getId(),
                    order.getTariffCodeSnapshot(), order.getTariffNameSnapshot(), vpnProvider.providerName(),
                    action == PaymentActivationAction.PROVISION ? null : access.getVersion(),
                    action == PaymentActivationAction.PROVISION ? null : access.getStatus(),
                    action == PaymentActivationAction.PROVISION ? null : access.getProviderName()));
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
    public Optional<PreparedPaymentActivation> fixProvisionTarget(
            PreparedPaymentActivation prepared,
            Instant target,
            Instant now
    ) {
        if (prepared == null || prepared.action() != PaymentActivationAction.PROVISION
                || prepared.targetExpiresAt() != null || target == null) {
            throw new PaymentOrderValidationException("Invalid provision target command");
        }
        PaymentOrder order = orders.findByIdForUpdate(prepared.paymentOrderId())
                .orElseThrow(PaymentNotFoundException::new);
        if (!matchesWithoutTarget(order, prepared)
                || order.getActivationTargetExpiresAt() != null) {
            return Optional.empty();
        }
        order.fixActivationTargetExpiresAt(prepared.token(), prepared.generation(), target, now);
        return Optional.of(prepared.withTargetExpiresAt(target));
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
            subscription = Subscription.builder().id(UUID.randomUUID()).account(order.getAccount()).tariff(tariff)
                    .status(SubscriptionStatus.ACTIVE).startsAt(now).expiresAt(p.targetExpiresAt())
                    .activatedAt(now).createdAt(now).updatedAt(now).build();
            subscriptions.save(subscription);
            VpnAccess access = accesses.findByAccountId(order.getAccount().getId()).orElse(null);
            if (access == null) {
                access = VpnAccess.builder().id(UUID.randomUUID()).account(order.getAccount())
                        .subscription(subscription).providerName(result.providerName())
                        .externalAccessId(result.externalAccessId()).configurationData(result.configurationData())
                        .status(VpnAccessStatus.ACTIVE).issuedAt(now).createdAt(now).updatedAt(now).build();
            } else {
                if (!access.getExternalAccessId().equals(result.externalAccessId())) {
                    throw new PaymentActivationResultMismatchException("Existing account VPN access differs from provider result");
                }
                access.attachSubscription(subscription, now);
            }
            access.requestPolicy(VpnEntitlement.PREMIUM, now, p.targetExpiresAt(), now);
            accesses.save(access);
            order.attachSubscription(subscription, now);
        } else if (p.action() == PaymentActivationAction.ACTIVATE_EXISTING) {
            VpnAccess access = accesses.findByIdForUpdate(p.existingVpnAccessId()).orElse(null);
            if (access == null || !access.getAccount().getId().equals(order.getAccount().getId())
                    || access.getStatus() != VpnAccessStatus.ACTIVE
                    || !Objects.equals(access.getVersion(), p.existingVpnAccessVersion())
                    || !Objects.equals(access.getProviderName(), p.existingVpnProviderName())
                    || !Objects.equals(access.getExternalAccessId(), p.stableExternalClientId())
                    || !Objects.equals(result.externalAccessId(), access.getExternalAccessId())) {
                return PaymentActivationOutcome.STALE;
            }
            subscription = Subscription.builder().id(UUID.randomUUID()).account(order.getAccount()).tariff(tariff)
                    .status(SubscriptionStatus.ACTIVE).startsAt(now).expiresAt(p.targetExpiresAt())
                    .activatedAt(now).createdAt(now).updatedAt(now).build();
            subscriptions.save(subscription);
            access.attachSubscription(subscription, now);
            access.requestPolicy(VpnEntitlement.PREMIUM, now, p.targetExpiresAt(), now);
            accesses.save(access);
            order.attachSubscription(subscription, now);
        } else {
            subscription = subscriptions.findByIdForUpdate(p.existingSubscriptionId()).orElseThrow(PaymentNotFoundException::new);
            VpnAccess access = accesses.findByIdForUpdate(p.existingVpnAccessId()).orElse(null);
            if (access == null) return PaymentActivationOutcome.STALE;
            if (!subscription.getAccount().getId().equals(order.getAccount().getId()) || !access.getId().equals(p.existingVpnAccessId())
                    || !access.getExternalAccessId().equals(p.stableExternalClientId()) || subscription.getStatus() != SubscriptionStatus.ACTIVE
                    || access.getStatus() != VpnAccessStatus.ACTIVE || !Objects.equals(access.getVersion(), p.existingVpnAccessVersion())
                    || !Objects.equals(access.getProviderName(), p.existingVpnProviderName())
                    || !result.externalAccessId().equals(access.getExternalAccessId())
                    || !Objects.equals(subscription.getVersion(), p.existingSubscriptionVersion())
                    || !subscription.getExpiresAt().equals(p.existingSubscriptionExpiresAt())) return PaymentActivationOutcome.STALE;
            subscription.setActivationTarget(tariff, p.targetExpiresAt(), now);
            subscriptions.save(subscription);
        }
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
        return matchesWithoutTarget(o, p)
                && Objects.equals(o.getActivationTargetExpiresAt(), p.targetExpiresAt());
    }
    private boolean matchesWithoutTarget(PaymentOrder o, PreparedPaymentActivation p) {
        return o.getId().equals(p.paymentOrderId()) && o.getAccount().getId().equals(p.accountId())
                && o.getStatus() == p.paymentStatus() && o.getProvider() == p.provider()
                && o.getActivationStatus() == p.activationStatus() && o.getActivationGeneration() == p.generation()
                && Objects.equals(o.getActivationClaimToken(), p.token()) && o.getActivationStatus() == PaymentActivationStatus.PROCESSING
                && Objects.equals(o.getDurationDaysSnapshot(), p.durationDays())
                && Objects.equals(o.getTariff().getId(), p.tariffId())
                && Objects.equals(o.getTariffCodeSnapshot(), p.tariffCodeSnapshot())
                && Objects.equals(o.getTariffNameSnapshot(), p.tariffNameSnapshot())
                && Objects.equals(o.getSubscription() == null ? null : o.getSubscription().getId(), p.existingSubscriptionId());
    }
    private void validateResult(PreparedPaymentActivation p, ProvisionedVpnAccess r) {
        if (r == null || r.externalAccessId() == null || r.externalAccessId().isBlank() || r.externalAccessId().length() > 128
                || r.targetExpiresAt() == null) throw new PaymentOrderValidationException("Invalid VPN provider result");
        if (!Objects.equals(r.externalAccessId(), p.stableExternalClientId())) mismatch("externalAccessId");
        if (!Objects.equals(r.providerName(), p.vpnProviderName())) mismatch("providerName");
        // 3x-ui expiryTime is a technical lifetime for the stable provider identity.
        // Business access expiry is the target stored on Subscription, so provisioning
        // must not bind it to the provider's technical expiry.
        if (p.action() != PaymentActivationAction.PROVISION) {
            Duration expiryDifference = Duration.between(p.targetExpiresAt(), r.targetExpiresAt()).abs();
            if (expiryDifference.compareTo(PROVIDER_EXPIRY_TOLERANCE) > 0) {
                mismatchExpiry(p.targetExpiresAt(), r.targetExpiresAt(), expiryDifference);
            }
        }
        if (p.action() == PaymentActivationAction.PROVISION && (r.configurationData() == null || r.configurationData().isBlank())) throw new PaymentOrderValidationException("Incomplete VPN provision result");
    }

    private void mismatch(String field) {
        log.warn("VPN provider result mismatch: field={}", field);
        throw new ru.murad.myvpn.exception.PaymentActivationResultMismatchException(
                "VPN provider result does not match activation snapshot");
    }

    private void mismatchExpiry(Instant expected, Instant actual, Duration difference) {
        log.warn("VPN provider result mismatch: field=targetExpiresAt expected={} actual={} differenceMillis={}",
                expected, actual, differenceMillis(difference));
        throw new ru.murad.myvpn.exception.PaymentActivationResultMismatchException(
                "VPN provider result does not match activation snapshot");
    }

    private long differenceMillis(Duration difference) {
        try {
            return difference.toMillis();
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private Instant plus(Instant base, java.time.Duration amount, String message) { try { return base.plus(amount).truncatedTo(ChronoUnit.MICROS); } catch (DateTimeException | ArithmeticException e) { throw new PaymentOrderValidationException(message + " cannot be applied"); } }
}
