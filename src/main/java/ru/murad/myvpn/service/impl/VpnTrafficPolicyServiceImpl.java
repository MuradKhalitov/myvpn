package ru.murad.myvpn.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.murad.myvpn.client.ProvisionedVpnAccess;
import ru.murad.myvpn.client.VpnProvider;
import ru.murad.myvpn.client.VpnProvisionRequest;
import ru.murad.myvpn.client.VpnTrafficPolicy;
import ru.murad.myvpn.config.VpnTrafficProperties;
import ru.murad.myvpn.model.VpnEntitlement;
import ru.murad.myvpn.service.VpnTrafficPolicyCandidate;
import ru.murad.myvpn.service.VpnTrafficPolicyService;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@Slf4j
public class VpnTrafficPolicyServiceImpl implements VpnTrafficPolicyService {
    private final VpnTrafficPolicyTransactionService transactions;
    private final AccountVpnAccessTransactionService freeAccessTransactions;
    private final VpnProvider provider;
    private final VpnTrafficProperties properties;
    private final Clock clock;

    public VpnTrafficPolicyServiceImpl(VpnTrafficPolicyTransactionService transactions,
            AccountVpnAccessTransactionService freeAccessTransactions, VpnProvider provider,
            VpnTrafficProperties properties, Clock clock) {
        this.transactions = transactions; this.freeAccessTransactions = freeAccessTransactions; this.provider = provider;
        this.properties = properties; this.clock = clock;
    }

    @Override
    public int reconcileDuePolicies() {
        Instant now = clock.instant();
        int applied = 0;
        for (VpnTrafficPolicyCandidate candidate : transactions.due(now)) {
            try {
                ProvisionedVpnAccess provisioned = candidate.requiresProvisioning()
                        ? provider.provision(new VpnProvisionRequest(candidate.accountId(), 0L,
                                candidate.provisioningExpiresAt(), candidate.externalAccessId(),
                                candidate.providerClientKey()))
                        : null;
                provider.applyTrafficPolicy(candidate.externalAccessId(), candidate.providerClientKey(), policy(candidate.entitlement()));
                provider.setAccessEnabled(candidate.externalAccessId(), candidate.entitlement() != VpnEntitlement.EXPIRED);
                boolean completed = candidate.requiresProvisioning()
                        ? freeAccessTransactions.completeFree(candidate.accessId(), candidate.generation(),
                                provisioned.providerName(), provisioned.configurationData(), now)
                        : transactions.complete(candidate.accessId(), candidate.generation(), now);
                if (completed) applied++;
                else log.info("Stale VPN traffic policy result ignored accessId={} generation={}",
                        candidate.accessId(), candidate.generation());
            } catch (RuntimeException exception) {
                if (!transactions.retry(candidate.accessId(), candidate.generation(), now)) {
                    log.info("Stale VPN traffic policy failure ignored accessId={} generation={}",
                            candidate.accessId(), candidate.generation());
                } else {
                    log.warn("VPN access reconciliation requires retry accessId={} entitlement={}",
                            candidate.accessId(), candidate.entitlement());
                }
            }
        }
        return applied;
    }

    @Override
    public void requestPremium(UUID accountId) {
        Instant now = clock.instant();
        transactions.request(accountId, VpnEntitlement.PREMIUM, null, null, now);
    }

    private VpnTrafficPolicy policy(VpnEntitlement entitlement) {
        return entitlement == VpnEntitlement.PREMIUM || entitlement == VpnEntitlement.TRIAL
                ? VpnTrafficPolicy.unlimited(properties.premiumUnlimitedTotalGb())
                : VpnTrafficPolicy.limited(properties.trafficLimitBytes());
    }
}
