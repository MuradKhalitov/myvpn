package ru.murad.myvpn.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import ru.murad.myvpn.client.ProvisionedVpnAccess;
import ru.murad.myvpn.dto.ActivateSubscriptionRequest;
import ru.murad.myvpn.dto.SubscriptionDto;
import ru.murad.myvpn.exception.SubscriptionNotFoundException;
import ru.murad.myvpn.exception.TelegramUserNotFoundException;
import ru.murad.myvpn.exception.VpnAccessNotFoundException;
import ru.murad.myvpn.exception.VpnTariffNotFoundException;
import ru.murad.myvpn.mapper.SubscriptionMapper;
import ru.murad.myvpn.model.Subscription;
import ru.murad.myvpn.model.Account;
import ru.murad.myvpn.model.SubscriptionStatus;
import ru.murad.myvpn.model.TelegramUser;
import ru.murad.myvpn.model.VpnAccess;
import ru.murad.myvpn.model.VpnAccessStatus;
import ru.murad.myvpn.model.VpnTariff;
import ru.murad.myvpn.repository.SubscriptionRepository;
import ru.murad.myvpn.repository.TelegramUserRepository;
import ru.murad.myvpn.repository.VpnAccessRepository;
import ru.murad.myvpn.repository.VpnTariffRepository;
import ru.murad.myvpn.service.ActivationPreparation;
import ru.murad.myvpn.service.SubscriptionTransactionService;
import ru.murad.myvpn.service.VpnAccessCandidate;
import ru.murad.myvpn.service.PendingProvisionCandidate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SubscriptionTransactionServiceImpl
        implements SubscriptionTransactionService {

    private final TelegramUserRepository userRepository;
    private final VpnTariffRepository tariffRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final VpnAccessRepository accessRepository;
    private final SubscriptionMapper subscriptionMapper;
    private final EntityManager entityManager;
    private static final int RECOVERY_BATCH_SIZE = 20;
    private static final int MAX_RECOVERY_ATTEMPTS = 5;
    private static final long RECOVERY_LEASE_MINUTES = 2;
    private static final long RECOVERY_RETRY_MINUTES = 5;

    @Override
    @Transactional
    public ActivationPreparation prepareActivation(
            ActivateSubscriptionRequest request,
            Instant now
    ) {
        TelegramUser user = userRepository.findByTelegramId(request.userTelegramId())
                .orElseThrow(() -> new TelegramUserNotFoundException(request.userTelegramId()));
        VpnTariff tariff = tariffRepository.findByCodeAndActiveTrue(request.tariffCode())
                .orElseThrow(() -> new VpnTariffNotFoundException(request.tariffCode()));

        Subscription active = subscriptionRepository
                .findFirstByUserIdAndStatusOrderByExpiresAtDesc(
                        user.getId(), SubscriptionStatus.ACTIVE)
                .orElse(null);
        if (active != null) {
            VpnAccess access = findAccess(active);
            if (active.getExpiresAt().isAfter(now)) {
                return new ActivationPreparation(
                        active.getId(),
                        user.getTelegramId(),
                        active.getExpiresAt().plus(
                                tariff.getDurationDays(), ChronoUnit.DAYS),
                        ActivationPreparation.Type.EXTEND,
                        access.getExternalAccessId());
            }
            return new ActivationPreparation(
                    active.getId(),
                    user.getTelegramId(),
                    active.getExpiresAt(),
                    ActivationPreparation.Type.REPLACE_EXPIRED,
                    access.getExternalAccessId());
        }
        if (subscriptionRepository.existsByUserIdAndStatus(
                user.getId(), SubscriptionStatus.PENDING)
                || subscriptionRepository.existsByUserIdAndStatus(
                user.getId(), SubscriptionStatus.RECONCILIATION_REQUIRED)
                || subscriptionRepository.existsByUserIdAndStatus(
                user.getId(), SubscriptionStatus.MANUAL_REVIEW_REQUIRED)) {
            throw new IllegalStateException("VPN provisioning is already pending");
        }

        Subscription subscription = Subscription.builder()
                .id(UUID.randomUUID())
                .user(user)
                .tariff(tariff)
                .status(SubscriptionStatus.PENDING)
                .startsAt(now)
                .expiresAt(now.plus(tariff.getDurationDays(), ChronoUnit.DAYS))
                .activatedByTelegramId(request.administratorTelegramId())
                .activatedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        subscriptionRepository.save(subscription);
        return new ActivationPreparation(
                subscription.getId(),
                user.getTelegramId(),
                subscription.getExpiresAt(),
                ActivationPreparation.Type.PROVISION,
                null);
    }

    @Override
    @Transactional
    public SubscriptionDto completeProvision(
            UUID subscriptionId,
            ProvisionedVpnAccess provisioned,
            Instant now
    ) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalStateException("Pending subscription not found"));
        if (subscription.getStatus() != SubscriptionStatus.PENDING
                && subscription.getStatus()
                != SubscriptionStatus.RECONCILIATION_REQUIRED) {
            throw new IllegalStateException("Subscription is not awaiting provisioning");
        }
        VpnAccess access = VpnAccess.builder()
                .id(UUID.randomUUID())
                .account(entityManager.getReference(Account.class, subscription.getUser().getId()))
                .subscription(subscription)
                .providerName(provisioned.providerName())
                .externalAccessId(provisioned.externalAccessId())
                .configurationData(configurationForPersistence(provisioned))
                .status(VpnAccessStatus.ACTIVE)
                .issuedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        accessRepository.save(access);
        subscription.activate(now);
        subscriptionRepository.save(subscription);
        return subscriptionMapper.toDto(subscription, access);
    }

    @Override
    @Transactional
    public boolean completeProvision(
            UUID subscriptionId,
            UUID claimToken,
            ProvisionedVpnAccess provisioned,
            Instant now
    ) {
        Subscription subscription = claimedSubscription(subscriptionId, claimToken);
        if (subscription == null) {
            return false;
        }
        VpnAccess access = VpnAccess.builder()
                .id(UUID.randomUUID())
                .account(entityManager.getReference(Account.class, subscription.getUser().getId()))
                .subscription(subscription)
                .providerName(provisioned.providerName())
                .externalAccessId(provisioned.externalAccessId())
                .configurationData(configurationForPersistence(provisioned))
                .status(VpnAccessStatus.ACTIVE)
                .issuedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        accessRepository.save(access);
        subscription.activate(now);
        subscriptionRepository.save(subscription);
        return true;
    }

    @Override
    @Transactional
    public SubscriptionDto completeExtension(
            UUID subscriptionId,
            String tariffCode,
            long administratorTelegramId,
            Instant expectedExpiry,
            Instant now
    ) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalStateException("Subscription not found"));
        requireStatus(subscription, SubscriptionStatus.ACTIVE);
        VpnTariff tariff = tariffRepository.findByCodeAndActiveTrue(tariffCode)
                .orElseThrow(() -> new VpnTariffNotFoundException(tariffCode));
        subscription.extend(tariff, administratorTelegramId, now);
        if (!subscription.getExpiresAt().equals(expectedExpiry)) {
            throw new IllegalStateException("Subscription changed concurrently");
        }
        subscriptionRepository.save(subscription);
        return subscriptionMapper.toDto(subscription, findAccess(subscription));
    }

    @Override
    @Transactional
    public void markProvisionFailed(UUID subscriptionId, Instant now) {
        subscriptionRepository.findById(subscriptionId)
                .filter(subscription -> subscription.getStatus() == SubscriptionStatus.PENDING
                        || subscription.getStatus()
                        == SubscriptionStatus.RECONCILIATION_REQUIRED)
                .ifPresent(subscription -> {
                    subscription.fail(now);
                    subscriptionRepository.save(subscription);
                });
    }

    @Override
    @Transactional
    public boolean markProvisionFailed(
            UUID subscriptionId,
            UUID claimToken,
            Instant now
    ) {
        Subscription subscription = claimedSubscription(subscriptionId, claimToken);
        if (subscription == null) {
            return false;
        }
        subscription.fail(now);
        subscriptionRepository.save(subscription);
        return true;
    }

    @Override
    @Transactional
    public void markProvisionReconciliationRequired(UUID subscriptionId, Instant now) {
        subscriptionRepository.findById(subscriptionId)
                .filter(subscription -> subscription.getStatus() == SubscriptionStatus.PENDING
                        || subscription.getStatus()
                        == SubscriptionStatus.RECONCILIATION_REQUIRED)
                .ifPresent(subscription -> subscription.requireReconciliation(
                        now, now.plus(RECOVERY_RETRY_MINUTES, ChronoUnit.MINUTES)));
    }

    @Override
    @Transactional(readOnly = true)
    public VpnAccessCandidate findActiveAccess(long userTelegramId) {
        Subscription subscription = subscriptionRepository
                .findFirstByUserTelegramIdAndStatusOrderByExpiresAtDesc(
                        userTelegramId, SubscriptionStatus.ACTIVE)
                .orElseThrow(() -> new SubscriptionNotFoundException(userTelegramId));
        VpnAccess access = findAccess(subscription);
        return new VpnAccessCandidate(subscription.getId(), access.getExternalAccessId());
    }

    @Override
    @Transactional
    public void completeRevocation(UUID subscriptionId, Instant now) {
        Subscription subscription = findSubscription(subscriptionId);
        requireStatus(subscription, SubscriptionStatus.ACTIVE);
        VpnAccess access = findAccess(subscription);
        access.revoke(now);
        subscription.revoke(now);
        accessRepository.save(access);
        subscriptionRepository.save(subscription);
    }

    @Override
    @Transactional
    public void completeExpiration(UUID subscriptionId, Instant now) {
        Subscription subscription = findSubscription(subscriptionId);
        requireStatus(subscription, SubscriptionStatus.ACTIVE);
        VpnAccess access = findAccess(subscription);
        access.revoke(now);
        subscription.markExpired(now);
        accessRepository.save(access);
        subscriptionRepository.save(subscription);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VpnAccessCandidate> findExpiredAccesses(Instant now) {
        return subscriptionRepository.findAllByStatusAndExpiresAtLessThanEqual(
                        SubscriptionStatus.ACTIVE, now)
                .stream()
                .map(subscription -> new VpnAccessCandidate(
                        subscription.getId(),
                        findAccess(subscription).getExternalAccessId()))
                .toList();
    }

    @Override
    @Transactional
    public List<PendingProvisionCandidate> claimPendingProvisioning(
            Instant staleBefore,
            Instant now,
            String owner
    ) {
        List<Subscription> subscriptions = subscriptionRepository
                .lockProvisioningCandidates(staleBefore, now,
                        MAX_RECOVERY_ATTEMPTS, RECOVERY_BATCH_SIZE);
        subscriptions.forEach(subscription -> subscription.claimProvisioning(
                owner,
                UUID.randomUUID(),
                now.plus(RECOVERY_LEASE_MINUTES, ChronoUnit.MINUTES),
                now));
        return subscriptions
                .stream()
                .map(subscription -> new PendingProvisionCandidate(
                        subscription.getId(),
                        subscription.getUser().getTelegramId(),
                        subscription.getExpiresAt(),
                        subscription.getProvisioningClaimToken(),
                        subscription.getProvisioningAttemptCount(),
                        MAX_RECOVERY_ATTEMPTS))
                .toList();
    }

    @Override
    @Transactional
    public boolean releaseProvisioningClaim(
            UUID subscriptionId,
            UUID claimToken,
            Instant now
    ) {
        Subscription subscription = claimedSubscription(subscriptionId, claimToken);
        if (subscription == null) {
            return false;
        }
        subscription.releaseProvisioningClaim(
                now.plus(RECOVERY_RETRY_MINUTES, ChronoUnit.MINUTES), now);
        subscriptionRepository.save(subscription);
        return true;
    }

    @Override
    @Transactional
    public boolean markManualReviewRequired(
            UUID subscriptionId,
            UUID claimToken,
            Instant now
    ) {
        Subscription subscription = claimedSubscription(subscriptionId, claimToken);
        if (subscription == null) {
            return false;
        }
        subscription.requireManualReview(now);
        subscriptionRepository.save(subscription);
        return true;
    }

    private Subscription claimedSubscription(UUID subscriptionId, UUID claimToken) {
        return subscriptionRepository.findByIdForUpdate(subscriptionId)
                .filter(subscription -> subscription.getStatus() == SubscriptionStatus.PENDING
                        || subscription.getStatus()
                        == SubscriptionStatus.RECONCILIATION_REQUIRED)
                .filter(subscription -> claimToken != null
                        && claimToken.equals(subscription.getProvisioningClaimToken()))
                .orElse(null);
    }

    private Subscription findSubscription(UUID subscriptionId) {
        return subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new IllegalStateException("Subscription not found"));
    }

    private VpnAccess findAccess(Subscription subscription) {
        return accessRepository.findBySubscriptionId(subscription.getId())
                .orElseThrow(() -> new VpnAccessNotFoundException(subscription.getId()));
    }

    private void requireStatus(Subscription subscription, SubscriptionStatus expected) {
        if (subscription.getStatus() != expected) {
            throw new IllegalStateException("Subscription state changed concurrently");
        }
    }

    private String configurationForPersistence(ProvisionedVpnAccess provisioned) {
        return "3X_UI".equals(provisioned.providerName())
                ? null : provisioned.configurationData();
    }
}
