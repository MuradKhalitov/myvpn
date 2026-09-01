package ru.murad.myvpn.application.subscription;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.murad.myvpn.mapper.SubscriptionMapper;
import ru.murad.myvpn.model.SubscriptionStatus;
import ru.murad.myvpn.repository.SubscriptionRepository;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.time.Clock;

@Service
@RequiredArgsConstructor
public class CurrentSubscriptionQueryService implements CurrentSubscriptionQuery {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionMapper subscriptionMapper;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public Optional<CurrentSubscriptionView> findCurrent(UUID userId) {
        Objects.requireNonNull(userId, "userId");
        return subscriptionRepository
                .findFirstByAccountIdAndStatusAndExpiresAtAfterOrderByExpiresAtDesc(
                        userId, SubscriptionStatus.ACTIVE, clock.instant())
                .map(subscriptionMapper::toCurrentSubscriptionView);
    }
}
