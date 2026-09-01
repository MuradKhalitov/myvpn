package ru.murad.myvpn.application.vpn;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.murad.myvpn.config.VpnTrafficProperties;
import ru.murad.myvpn.model.VpnAccess;
import ru.murad.myvpn.model.VpnAccessStatus;
import ru.murad.myvpn.model.VpnEntitlement;
import ru.murad.myvpn.model.SubscriptionStatus;
import ru.murad.myvpn.repository.SubscriptionRepository;
import ru.murad.myvpn.repository.VpnAccessRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CurrentVpnAccessQueryService implements CurrentVpnAccessQuery {

    private final SubscriptionRepository subscriptionRepository;
    private final VpnAccessRepository vpnAccessRepository;
    private final VpnTrafficProperties trafficProperties;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public VpnAccessResponse getCurrentAccess(UUID accountId) {
        Instant now = clock.instant();
        VpnAccess access = vpnAccessRepository.findByAccountId(accountId).orElse(null);
        var premium = subscriptionRepository
                .findFirstByAccountIdAndStatusAndExpiresAtAfterOrderByExpiresAtDesc(
                        accountId, SubscriptionStatus.ACTIVE, now)
                .orElse(null);
        VpnEntitlement entitlement = premium == null ? VpnEntitlement.FREE : VpnEntitlement.PREMIUM;
        if (access == null) {
            return new VpnAccessResponse(VpnAccessApiStatus.PROVISIONING, entitlement, null,
                    entitlement == VpnEntitlement.PREMIUM ? null : new VpnQuotaResponse(
                            trafficProperties.trafficLimitBytes(), null, null),
                    premium == null ? null : premium.getExpiresAt());
        }
        VpnAccessApiStatus status = access.getStatus() == VpnAccessStatus.ACTIVE
                && access.getConfigurationData() != null && !access.getConfigurationData().isBlank()
                ? VpnAccessApiStatus.READY
                : access.getStatus() == VpnAccessStatus.REVOKED
                    || access.getPolicyStatus() == ru.murad.myvpn.model.VpnPolicyStatus.RETRY_REQUIRED
                    ? VpnAccessApiStatus.RETRY_REQUIRED : VpnAccessApiStatus.PROVISIONING;
        VpnQuotaResponse quota = entitlement == VpnEntitlement.PREMIUM ? null : new VpnQuotaResponse(
                trafficProperties.trafficLimitBytes(), access.getQuotaPeriodStartedAt(), access.getQuotaPeriodEndsAt());
        return new VpnAccessResponse(status, entitlement,
                status == VpnAccessApiStatus.READY ? access.getConfigurationData() : null,
                quota, premium == null ? null : premium.getExpiresAt());
    }
}
