package ru.murad.myvpn.application.vpn;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.murad.myvpn.mapper.SubscriptionMapper;
import ru.murad.myvpn.model.SubscriptionStatus;
import ru.murad.myvpn.repository.SubscriptionRepository;
import ru.murad.myvpn.repository.VpnAccessRepository;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CurrentVpnAccessQueryService implements CurrentVpnAccessQuery {

    private final SubscriptionRepository subscriptionRepository;
    private final VpnAccessRepository vpnAccessRepository;
    private final SubscriptionMapper subscriptionMapper;

    @Override
    @Transactional(readOnly = true)
    public Optional<VpnAccessView> findCurrent(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        return subscriptionRepository
                .findFirstByUserIdAndStatusOrderByExpiresAtDesc(
                        userId, SubscriptionStatus.ACTIVE)
                .flatMap(subscription -> vpnAccessRepository
                        .findBySubscriptionId(subscription.getId()))
                .map(subscriptionMapper::toVpnAccessView);
    }
}
