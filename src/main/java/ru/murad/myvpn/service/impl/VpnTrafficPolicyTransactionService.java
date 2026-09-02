package ru.murad.myvpn.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.murad.myvpn.model.VpnEntitlement;
import ru.murad.myvpn.model.VpnPolicyStatus;
import ru.murad.myvpn.model.VpnAccessStatus;
import ru.murad.myvpn.repository.VpnAccessRepository;
import ru.murad.myvpn.service.VpnTrafficPolicyCandidate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class VpnTrafficPolicyTransactionService {
    private final VpnAccessRepository accesses;

    public VpnTrafficPolicyTransactionService(VpnAccessRepository accesses) { this.accesses = accesses; }

    @Transactional(readOnly = true)
    public List<VpnTrafficPolicyCandidate> due(Instant now) {
        return accesses.findTop50ByPolicyStatusInAndNextPolicyAttemptAtLessThanEqualOrderByUpdatedAt(
                        List.of(VpnPolicyStatus.PENDING, VpnPolicyStatus.RETRY_REQUIRED), now)
                .stream().filter(access -> access.getStatus() != VpnAccessStatus.REVOKED
                        && access.getProviderClientKey() != null)
                .map(access -> new VpnTrafficPolicyCandidate(access.getId(), access.getAccount().getId(),
                        access.getExternalAccessId(), access.getProviderClientKey(), access.getDesiredEntitlement(),
                        access.getPolicyGeneration(), access.getIssuedAt().plus(
                                AccountVpnAccessServiceImpl.FREE_PROVISIONING_DURATION_DAYS, ChronoUnit.DAYS),
                        access.getStatus() == VpnAccessStatus.PROVISIONING))
                .toList();
    }

    @Transactional
    public Optional<VpnTrafficPolicyCandidate> request(UUID accountId, VpnEntitlement entitlement,
            Instant quotaStart, Instant quotaEnd, Instant now) {
        return accesses.findByAccountId(accountId).map(access -> {
            access.requestPolicy(entitlement, quotaStart, quotaEnd, now);
            return new VpnTrafficPolicyCandidate(access.getId(), access.getAccount().getId(),
                    access.getExternalAccessId(), access.getProviderClientKey(), access.getDesiredEntitlement(),
                    access.getPolicyGeneration(), null, false);
        });
    }

    @Transactional
    public boolean complete(UUID accessId, long generation, Instant now) {
        return accesses.findByIdForUpdate(accessId).map(access -> access.applyPolicy(generation, now)).orElse(false);
    }

    @Transactional
    public boolean retry(UUID accessId, long generation, Instant now) {
        return accesses.findByIdForUpdate(accessId)
                .map(access -> access.retryPolicy(generation, now.plus(1, ChronoUnit.MINUTES), now))
                .orElse(false);
    }
}
