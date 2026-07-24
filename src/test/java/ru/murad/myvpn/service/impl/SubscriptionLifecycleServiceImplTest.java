package ru.murad.myvpn.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.murad.myvpn.client.VpnProvider;
import ru.murad.myvpn.model.Subscription;
import ru.murad.myvpn.model.SubscriptionStatus;
import ru.murad.myvpn.model.VpnAccess;
import ru.murad.myvpn.model.VpnAccessStatus;
import ru.murad.myvpn.repository.SubscriptionRepository;
import ru.murad.myvpn.repository.VpnAccessRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionLifecycleServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private VpnAccessRepository accessRepository;
    @Mock private VpnProvider vpnProvider;

    private SubscriptionLifecycleServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SubscriptionLifecycleServiceImpl(
                subscriptionRepository,
                accessRepository,
                vpnProvider,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void shouldRevokeExpiredSubscription() {
        Subscription subscription = Subscription.builder()
                .id(UUID.randomUUID())
                .status(SubscriptionStatus.ACTIVE)
                .expiresAt(NOW.minusSeconds(1))
                .updatedAt(NOW.minusSeconds(1))
                .build();
        VpnAccess access = revokedCandidate(subscription, NOW.minusSeconds(1));
        when(subscriptionRepository.findAllByStatusAndExpiresAtLessThanEqual(
                SubscriptionStatus.ACTIVE, NOW)).thenReturn(List.of(subscription));
        when(accessRepository.findBySubscriptionId(subscription.getId()))
                .thenReturn(java.util.Optional.of(access));

        int processed = service.revokeExpiredSubscriptions();

        assertThat(processed).isOne();
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        assertThat(access.getStatus()).isEqualTo(VpnAccessStatus.REVOKED);
        assertThat(access.getRevokedAt()).isEqualTo(NOW);
        verify(vpnProvider).revoke("external-1");
    }

    @Test
    void shouldDeleteConfigurationAfterThirtyDays() {
        Subscription subscription = Subscription.builder().id(UUID.randomUUID()).build();
        Instant revokedAt = NOW.minus(30, ChronoUnit.DAYS);
        VpnAccess access = revokedCandidate(subscription, revokedAt);
        access.revoke(revokedAt);
        when(accessRepository
                .findAllByStatusAndRevokedAtLessThanEqualAndConfigurationDataIsNotNull(
                        VpnAccessStatus.REVOKED, revokedAt))
                .thenReturn(List.of(access));

        int processed = service.deleteExpiredConfigurations();

        assertThat(processed).isOne();
        assertThat(access.getConfigurationData()).isNull();
        assertThat(access.getConfigurationDeletedAt()).isEqualTo(NOW);
        verify(accessRepository).saveAll(List.of(access));
    }

    private VpnAccess revokedCandidate(Subscription subscription, Instant updatedAt) {
        return VpnAccess.builder()
                .id(UUID.randomUUID())
                .subscription(subscription)
                .providerName("FAKE")
                .externalAccessId("external-1")
                .configurationData("fake-config")
                .status(VpnAccessStatus.ACTIVE)
                .issuedAt(updatedAt)
                .createdAt(updatedAt)
                .updatedAt(updatedAt)
                .build();
    }
}
