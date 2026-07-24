package ru.murad.myvpn.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.murad.myvpn.client.VpnProvider;
import ru.murad.myvpn.client.VpnProvisionRequest;
import ru.murad.myvpn.model.VpnAccess;
import ru.murad.myvpn.model.VpnAccessStatus;
import ru.murad.myvpn.repository.VpnAccessRepository;
import ru.murad.myvpn.service.SubscriptionLifecycleService;
import ru.murad.myvpn.service.SubscriptionTransactionService;
import ru.murad.myvpn.service.VpnAccessCandidate;
import ru.murad.myvpn.service.PendingProvisionCandidate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionLifecycleServiceImpl implements SubscriptionLifecycleService {

    private static final long CONFIGURATION_RETENTION_DAYS = 30;
    private static final long PENDING_STALE_MINUTES = 5;
    private final String recoveryOwner = UUID.randomUUID().toString();

    private final VpnAccessRepository accessRepository;
    private final VpnProvider vpnProvider;
    private final SubscriptionTransactionService transactionService;
    private final Clock clock;

    @Override
    public int revokeExpiredSubscriptions() {
        Instant now = clock.instant();
        List<VpnAccessCandidate> candidates = transactionService.findExpiredAccesses(now);
        int processed = 0;
        for (VpnAccessCandidate candidate : candidates) {
            try {
                vpnProvider.revoke(candidate.externalAccessId());
                transactionService.completeExpiration(candidate.subscriptionId(), now);
                processed++;
            } catch (RuntimeException exception) {
                log.warn("VPN expiration operation failed");
            }
        }
        return processed;
    }

    @Override
    @Transactional
    public int deleteExpiredConfigurations() {
        Instant now = clock.instant();
        Instant threshold = now.minus(CONFIGURATION_RETENTION_DAYS, ChronoUnit.DAYS);
        List<VpnAccess> accesses = accessRepository
                .findAllByStatusAndRevokedAtLessThanEqualAndConfigurationDataIsNotNull(
                        VpnAccessStatus.REVOKED, threshold);
        accesses.forEach(access -> access.deleteConfiguration(now));
        accessRepository.saveAll(accesses);
        return accesses.size();
    }

    @Override
    public int recoverPendingSubscriptions() {
        Instant now = clock.instant();
        List<PendingProvisionCandidate> candidates =
                transactionService.claimPendingProvisioning(
                        now.minus(PENDING_STALE_MINUTES, ChronoUnit.MINUTES),
                        now,
                        recoveryOwner);
        int processed = 0;
        for (PendingProvisionCandidate candidate : candidates) {
            ru.murad.myvpn.client.ProvisionedVpnAccess provisioned;
            try {
                provisioned = vpnProvider.provision(new VpnProvisionRequest(
                        candidate.subscriptionId(),
                        candidate.userTelegramId(),
                        candidate.expiresAt()));
            } catch (ru.murad.myvpn.exception.ThreeXUiUncertainException exception) {
                boolean updated = candidate.isLastAllowedAttempt()
                        ? transactionService.markManualReviewRequired(
                                candidate.subscriptionId(), candidate.claimToken(), now)
                        : transactionService.releaseProvisioningClaim(
                                candidate.subscriptionId(), candidate.claimToken(), now);
                if (!updated) {
                    log.info("Stale VPN recovery result was ignored");
                } else if (candidate.isLastAllowedAttempt()) {
                    log.warn("VPN provisioning requires manual review");
                } else {
                    log.warn("Pending VPN provisioning still requires reconciliation");
                }
                continue;
            } catch (RuntimeException exception) {
                if (!transactionService.markProvisionFailed(
                        candidate.subscriptionId(), candidate.claimToken(), now)) {
                    log.info("Stale VPN recovery result was ignored");
                } else {
                    log.warn("Pending VPN provisioning recovery failed");
                }
                continue;
            }
            try {
                if (transactionService.completeProvision(
                        candidate.subscriptionId(),
                        candidate.claimToken(),
                        provisioned,
                        now)) {
                    processed++;
                } else {
                    log.info("Stale VPN recovery result was ignored");
                }
            } catch (RuntimeException exception) {
                log.warn("Recovered VPN provisioning could not be persisted");
            }
        }
        return processed;
    }
}
