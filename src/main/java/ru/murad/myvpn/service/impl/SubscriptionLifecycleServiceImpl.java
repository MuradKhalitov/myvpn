package ru.murad.myvpn.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.murad.myvpn.client.VpnProvider;
import ru.murad.myvpn.exception.VpnAccessNotFoundException;
import ru.murad.myvpn.model.Subscription;
import ru.murad.myvpn.model.SubscriptionStatus;
import ru.murad.myvpn.model.VpnAccess;
import ru.murad.myvpn.model.VpnAccessStatus;
import ru.murad.myvpn.repository.SubscriptionRepository;
import ru.murad.myvpn.repository.VpnAccessRepository;
import ru.murad.myvpn.service.SubscriptionLifecycleService;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SubscriptionLifecycleServiceImpl implements SubscriptionLifecycleService {

    private static final long CONFIGURATION_RETENTION_DAYS = 30;

    private final SubscriptionRepository subscriptionRepository;
    private final VpnAccessRepository accessRepository;
    private final VpnProvider vpnProvider;
    private final Clock clock;

    @Override
    @Transactional
    public int revokeExpiredSubscriptions() {
        Instant now = clock.instant();
        List<Subscription> subscriptions =
                subscriptionRepository.findAllByStatusAndExpiresAtLessThanEqual(
                        SubscriptionStatus.ACTIVE, now);
        subscriptions.forEach(subscription -> expire(subscription, now));
        return subscriptions.size();
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

    private void expire(Subscription subscription, Instant now) {
        VpnAccess access = accessRepository.findBySubscriptionId(subscription.getId())
                .orElseThrow(() -> new VpnAccessNotFoundException(subscription.getId()));
        vpnProvider.revoke(access.getExternalAccessId());
        access.revoke(now);
        subscription.markExpired(now);
        accessRepository.save(access);
        subscriptionRepository.save(subscription);
    }
}
