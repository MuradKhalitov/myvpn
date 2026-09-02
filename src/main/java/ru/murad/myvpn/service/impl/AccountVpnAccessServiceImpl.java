package ru.murad.myvpn.service.impl;

import org.springframework.stereotype.Service;
import ru.murad.myvpn.client.ProvisionedVpnAccess;
import ru.murad.myvpn.client.VpnProvider;
import ru.murad.myvpn.client.VpnProvisionRequest;
import ru.murad.myvpn.client.VpnTrafficPolicy;
import ru.murad.myvpn.config.VpnTrafficProperties;
import ru.murad.myvpn.model.VpnAccess;
import ru.murad.myvpn.service.AccountVpnAccessService;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class AccountVpnAccessServiceImpl implements AccountVpnAccessService {
    static final long FREE_PROVISIONING_DURATION_DAYS = 3650;
    private final AccountVpnAccessTransactionService transactions;
    private final VpnProvider provider;
    private final VpnTrafficProperties traffic;
    private final Clock clock;

    public AccountVpnAccessServiceImpl(AccountVpnAccessTransactionService transactions, VpnProvider provider,
            VpnTrafficProperties traffic, Clock clock) {
        this.transactions = transactions; this.provider = provider; this.traffic = traffic; this.clock = clock;
    }

    @Override
    public UUID ensureFreeVpnAccess(UUID accountId) {
        Instant now = clock.instant();
        VpnAccess access = transactions.reserveFree(accountId, provider.providerName(), now,
                now.plus(traffic.quotaPeriodDays(), ChronoUnit.DAYS));
        if (access.getStatus() == ru.murad.myvpn.model.VpnAccessStatus.ACTIVE
                && access.getAppliedEntitlement() == ru.murad.myvpn.model.VpnEntitlement.FREE) return access.getId();
        // This is deliberately account-only: the legacy request fields are not used for ownership.
        ProvisionedVpnAccess provisioned = provider.provision(new VpnProvisionRequest(accountId, 0L,
                access.getIssuedAt().plus(FREE_PROVISIONING_DURATION_DAYS, ChronoUnit.DAYS),
                access.getExternalAccessId(), access.getProviderClientKey()));
        provider.applyTrafficPolicy(access.getExternalAccessId(), access.getProviderClientKey(),
                VpnTrafficPolicy.limited(traffic.trafficLimitBytes()));
        if (!transactions.completeFree(access.getId(), access.getPolicyGeneration(),
                provisioned.providerName(), provisioned.configurationData(), clock.instant())) {
            throw new IllegalStateException("VPN access policy changed while provisioning");
        }
        return access.getId();
    }
}
