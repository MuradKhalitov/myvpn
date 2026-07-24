package ru.murad.myvpn.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.murad.myvpn.client.VpnProvider;
import ru.murad.myvpn.model.Subscription;
import ru.murad.myvpn.model.VpnAccess;
import ru.murad.myvpn.model.VpnAccessStatus;
import ru.murad.myvpn.repository.VpnAccessRepository;
import ru.murad.myvpn.service.SubscriptionTransactionService;
import ru.murad.myvpn.service.VpnAccessCandidate;
import ru.murad.myvpn.service.PendingProvisionCandidate;
import ru.murad.myvpn.client.ProvisionedVpnAccess;
import ru.murad.myvpn.client.VpnProvisionRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class SubscriptionLifecycleServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");

    @Mock private VpnAccessRepository accessRepository;
    @Mock private VpnProvider vpnProvider;
    @Mock private SubscriptionTransactionService transactionService;

    private SubscriptionLifecycleServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SubscriptionLifecycleServiceImpl(
                accessRepository,
                vpnProvider,
                transactionService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldRevokeExpiredSubscriptionBeforeCommittingExpiration() {
        UUID subscriptionId = UUID.randomUUID();
        when(transactionService.findExpiredAccesses(NOW))
                .thenReturn(List.of(new VpnAccessCandidate(subscriptionId, "external")));

        assertThat(service.revokeExpiredSubscriptions()).isOne();

        verify(vpnProvider).revoke("external");
        verify(transactionService).completeExpiration(subscriptionId, NOW);
    }

    @Test
    void shouldContinueWhenOneExpiredAccessFails() {
        UUID failed = UUID.randomUUID();
        UUID successful = UUID.randomUUID();
        when(transactionService.findExpiredAccesses(NOW)).thenReturn(List.of(
                new VpnAccessCandidate(failed, "failed"),
                new VpnAccessCandidate(successful, "successful")));
        org.mockito.Mockito.doThrow(new IllegalStateException())
                .when(vpnProvider).revoke("failed");

        assertThat(service.revokeExpiredSubscriptions()).isOne();

        verify(transactionService).completeExpiration(successful, NOW);
    }

    @Test
    void shouldDeleteConfigurationAfterThirtyDays() {
        Subscription subscription = Subscription.builder().id(UUID.randomUUID()).build();
        Instant revokedAt = NOW.minus(30, ChronoUnit.DAYS);
        VpnAccess access = VpnAccess.builder()
                .id(UUID.randomUUID())
                .subscription(subscription)
                .providerName("FAKE")
                .externalAccessId("external")
                .configurationData("configuration")
                .status(VpnAccessStatus.ACTIVE)
                .issuedAt(revokedAt)
                .createdAt(revokedAt)
                .updatedAt(revokedAt)
                .build();
        access.revoke(revokedAt);
        when(accessRepository
                .findAllByStatusAndRevokedAtLessThanEqualAndConfigurationDataIsNotNull(
                        VpnAccessStatus.REVOKED, revokedAt))
                .thenReturn(List.of(access));

        assertThat(service.deleteExpiredConfigurations()).isOne();
        assertThat(access.getConfigurationData()).isNull();
        verify(accessRepository).saveAll(List.of(access));
    }

    @Test
    void shouldRecoverStalePendingProvisioning() {
        UUID subscriptionId = UUID.randomUUID();
        Instant expiresAt = NOW.plus(30, ChronoUnit.DAYS);
        PendingProvisionCandidate candidate =
                new PendingProvisionCandidate(
                        subscriptionId, 123L, expiresAt, UUID.randomUUID(), 1, 5);
        ProvisionedVpnAccess provisioned =
                new ProvisionedVpnAccess("FAKE", "external", "configuration");
        when(transactionService.claimPendingProvisioning(
                org.mockito.ArgumentMatchers.eq(NOW.minus(5, ChronoUnit.MINUTES)),
                org.mockito.ArgumentMatchers.eq(NOW),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of(candidate));
        when(vpnProvider.provision(
                new VpnProvisionRequest(subscriptionId, 123L, expiresAt)))
                .thenReturn(provisioned);
        when(transactionService.completeProvision(
                subscriptionId, candidate.claimToken(), provisioned, NOW))
                .thenReturn(true);

        assertThat(service.recoverPendingSubscriptions()).isOne();

        verify(transactionService).completeProvision(
                subscriptionId, candidate.claimToken(), provisioned, NOW);
    }

    @Test
    void lastUncertainRecoveryAttemptMustRequireManualReview() {
        UUID subscriptionId = UUID.randomUUID();
        UUID claimToken = UUID.randomUUID();
        PendingProvisionCandidate candidate = new PendingProvisionCandidate(
                subscriptionId, 123L, NOW.plus(30, ChronoUnit.DAYS),
                claimToken, 5, 5);
        when(transactionService.claimPendingProvisioning(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(NOW),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of(candidate));
        doThrow(new ru.murad.myvpn.exception.ThreeXUiUncertainException())
                .when(vpnProvider).provision(org.mockito.ArgumentMatchers.any());
        when(transactionService.markManualReviewRequired(
                subscriptionId, claimToken, NOW)).thenReturn(true);

        assertThat(service.recoverPendingSubscriptions()).isZero();

        verify(transactionService).markManualReviewRequired(
                subscriptionId, claimToken, NOW);
    }

    @Test
    void successOnLastRecoveryAttemptMustCompleteInsteadOfManualReview() {
        UUID subscriptionId = UUID.randomUUID();
        UUID claimToken = UUID.randomUUID();
        Instant expiresAt = NOW.plus(30, ChronoUnit.DAYS);
        PendingProvisionCandidate candidate = new PendingProvisionCandidate(
                subscriptionId, 123L, expiresAt, claimToken, 5, 5);
        ProvisionedVpnAccess provisioned =
                new ProvisionedVpnAccess("TEST", "external", null);
        when(transactionService.claimPendingProvisioning(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(NOW),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of(candidate));
        when(vpnProvider.provision(
                new VpnProvisionRequest(subscriptionId, 123L, expiresAt)))
                .thenReturn(provisioned);
        when(transactionService.completeProvision(
                subscriptionId, claimToken, provisioned, NOW)).thenReturn(true);

        assertThat(service.recoverPendingSubscriptions()).isOne();

        verify(transactionService, org.mockito.Mockito.never())
                .markManualReviewRequired(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }
}
