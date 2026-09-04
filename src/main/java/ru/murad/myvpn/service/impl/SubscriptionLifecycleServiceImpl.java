package ru.murad.myvpn.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.murad.myvpn.config.VpnTrafficProperties;
import ru.murad.myvpn.model.SubscriptionStatus;
import ru.murad.myvpn.model.VpnAccessStatus;
import ru.murad.myvpn.model.VpnEntitlement;
import ru.murad.myvpn.repository.SubscriptionRepository;
import ru.murad.myvpn.repository.VpnAccessRepository;
import ru.murad.myvpn.repository.AccountRepository;
import ru.murad.myvpn.service.SubscriptionLifecycleService;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionLifecycleServiceImpl implements SubscriptionLifecycleService {
    private static final long CONFIGURATION_RETENTION_DAYS = 30;
    private final SubscriptionRepository subscriptions;
    private final AccountRepository accounts;
    private final VpnAccessRepository accesses;
    private final VpnTrafficPolicyTransactionService policyTransactions;
    private final VpnTrafficProperties traffic;
    private final Clock clock;

    @Override
    @Transactional
    public int revokeExpiredSubscriptions() {
        Instant now = clock.instant();
        int processed = 0;
        for (var subscription : subscriptions.findAllByStatusAndExpiresAtLessThanEqual(SubscriptionStatus.ACTIVE, now)) {
            var access = accesses.findBySubscriptionId(subscription.getId()).orElse(null);
            try {
                if (access != null && access.getStatus() == VpnAccessStatus.ACTIVE) {
                    policyTransactions.request(access.getAccount().getId(), VpnEntitlement.EXPIRED,
                            null, null, now);
                }
                subscription.markExpired(now);
                subscriptions.save(subscription);
                processed++;
            } catch (RuntimeException ex) {
                log.warn("VPN expiration operation failed");
            }
        }
        return processed;
    }

    @Override
    @Transactional
    public int expireTrials() {
        Instant now = clock.instant(); int processed = 0;
        for (var account : accounts.findAllByTrialGrantedAtIsNotNullAndTrialExpiresAtLessThanEqual(now)) {
            boolean premium = subscriptions.findFirstByAccountIdAndStatusAndExpiresAtAfterOrderByExpiresAtDesc(
                    account.getId(), SubscriptionStatus.ACTIVE, now).isPresent();
            if (!premium) accesses.findByAccountId(account.getId()).filter(access -> access.getStatus() == VpnAccessStatus.ACTIVE
                            && access.getDesiredEntitlement() != VpnEntitlement.EXPIRED)
                    .ifPresent(access -> policyTransactions.request(account.getId(), VpnEntitlement.EXPIRED, null, null, now));
            processed++;
        }
        return processed;
    }

    @Override
    @Transactional
    public int deleteExpiredConfigurations() {
        Instant threshold = clock.instant().minus(CONFIGURATION_RETENTION_DAYS, ChronoUnit.DAYS);
        var expired = accesses.findAllByStatusAndRevokedAtLessThanEqualAndConfigurationDataIsNotNull(
                VpnAccessStatus.REVOKED, threshold);
        Instant now = clock.instant();
        expired.forEach(access -> access.deleteConfiguration(now));
        accesses.saveAll(expired);
        return expired.size();
    }

    @Override
    public int recoverPendingSubscriptions() {
        // Pending VPN reconciliation is owned by the policy/reconciliation scheduler.
        return 0;
    }
}
