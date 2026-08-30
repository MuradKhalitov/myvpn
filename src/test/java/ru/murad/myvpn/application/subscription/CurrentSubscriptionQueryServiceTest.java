package ru.murad.myvpn.application.subscription;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.murad.myvpn.dto.VpnTariffDto;
import ru.murad.myvpn.mapper.SubscriptionMapper;
import ru.murad.myvpn.model.Subscription;
import ru.murad.myvpn.model.SubscriptionStatus;
import ru.murad.myvpn.repository.SubscriptionRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CurrentSubscriptionQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-30T10:00:00Z");

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private SubscriptionMapper subscriptionMapper;

    @Test
    void returnsEmptyWhenCurrentSubscriptionDoesNotExist() {
        UUID userId = UUID.randomUUID();
        var service = service();

        assertThat(service.findCurrent(userId)).isEmpty();

        verify(subscriptionRepository)
                .findFirstByUserIdAndStatusAndExpiresAtAfterOrderByExpiresAtDesc(
                        userId, SubscriptionStatus.ACTIVE, NOW);
    }

    @Test
    void returnsActiveSubscriptionForInternalUserId() {
        UUID userId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = Subscription.builder()
                .id(subscriptionId)
                .status(SubscriptionStatus.ACTIVE)
                .startsAt(NOW.minusSeconds(60))
                .expiresAt(NOW.plusSeconds(3600))
                .build();
        CurrentSubscriptionView expected = new CurrentSubscriptionView(
                subscriptionId,
                new VpnTariffDto(UUID.randomUUID(), "MONTH_1", "Month", "VPN",
                        30, new BigDecimal("90.00"), "RUB"),
                SubscriptionStatus.ACTIVE,
                subscription.getStartsAt(), subscription.getExpiresAt());
        when(subscriptionRepository
                .findFirstByUserIdAndStatusAndExpiresAtAfterOrderByExpiresAtDesc(
                        userId, SubscriptionStatus.ACTIVE, NOW))
                .thenReturn(Optional.of(subscription));
        when(subscriptionMapper.toCurrentSubscriptionView(subscription))
                .thenReturn(expected);

        assertThat(service().findCurrent(userId)).contains(expected);
    }

    private CurrentSubscriptionQueryService service() {
        return new CurrentSubscriptionQueryService(
                subscriptionRepository, subscriptionMapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
