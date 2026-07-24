package ru.murad.myvpn.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.murad.myvpn.client.ProvisionedVpnAccess;
import ru.murad.myvpn.client.VpnExtensionRequest;
import ru.murad.myvpn.client.VpnProvider;
import ru.murad.myvpn.client.VpnProvisionRequest;
import ru.murad.myvpn.dto.ActivateSubscriptionRequest;
import ru.murad.myvpn.dto.RevokeSubscriptionRequest;
import ru.murad.myvpn.dto.SubscriptionDto;
import ru.murad.myvpn.exception.AdministratorAccessDeniedException;
import ru.murad.myvpn.mapper.SubscriptionMapper;
import ru.murad.myvpn.repository.SubscriptionRepository;
import ru.murad.myvpn.repository.VpnAccessRepository;
import ru.murad.myvpn.service.ActivationPreparation;
import ru.murad.myvpn.service.AdminAuthorizationService;
import ru.murad.myvpn.service.SubscriptionTransactionService;
import ru.murad.myvpn.service.VpnAccessCandidate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
    private static final long ADMIN_ID = 101L;
    private static final long USER_ID = 202L;

    @Mock private AdminAuthorizationService authorizationService;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private VpnAccessRepository accessRepository;
    @Mock private VpnProvider vpnProvider;
    @Mock private SubscriptionMapper subscriptionMapper;
    @Mock private SubscriptionTransactionService transactionService;

    private SubscriptionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SubscriptionServiceImpl(
                authorizationService,
                subscriptionRepository,
                accessRepository,
                vpnProvider,
                subscriptionMapper,
                transactionService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldProvisionOutsidePreparationTransactionAndCompleteAfterConfirmation() {
        UUID subscriptionId = UUID.randomUUID();
        ActivateSubscriptionRequest request =
                new ActivateSubscriptionRequest(ADMIN_ID, USER_ID, "MONTH_1");
        ActivationPreparation preparation = new ActivationPreparation(
                subscriptionId, USER_ID, NOW.plusSeconds(3600),
                ActivationPreparation.Type.PROVISION, null);
        ProvisionedVpnAccess provisioned =
                new ProvisionedVpnAccess("FAKE", "external", "configuration");
        SubscriptionDto expected = org.mockito.Mockito.mock(SubscriptionDto.class);
        when(transactionService.prepareActivation(request, NOW)).thenReturn(preparation);
        when(vpnProvider.provision(new VpnProvisionRequest(
                subscriptionId, USER_ID, preparation.expiresAt())))
                .thenReturn(provisioned);
        when(transactionService.completeProvision(subscriptionId, provisioned, NOW))
                .thenReturn(expected);

        assertThat(service.activate(request)).isSameAs(expected);

        verify(transactionService).prepareActivation(request, NOW);
        verify(vpnProvider).provision(new VpnProvisionRequest(
                subscriptionId, USER_ID, preparation.expiresAt()));
        verify(transactionService).completeProvision(subscriptionId, provisioned, NOW);
    }

    @Test
    void shouldMarkPendingSubscriptionFailedWhenProvisioningFails() {
        UUID subscriptionId = UUID.randomUUID();
        ActivateSubscriptionRequest request =
                new ActivateSubscriptionRequest(ADMIN_ID, USER_ID, "MONTH_1");
        ActivationPreparation preparation = new ActivationPreparation(
                subscriptionId, USER_ID, NOW.plusSeconds(3600),
                ActivationPreparation.Type.PROVISION, null);
        when(transactionService.prepareActivation(request, NOW)).thenReturn(preparation);
        doThrow(new IllegalStateException("provider failure"))
                .when(vpnProvider).provision(new VpnProvisionRequest(
                        subscriptionId, USER_ID, preparation.expiresAt()));

        assertThatThrownBy(() -> service.activate(request))
                .isInstanceOf(IllegalStateException.class);

        verify(transactionService).markProvisionFailed(subscriptionId, NOW);
    }

    @Test
    void shouldLeaveUncertainProvisioningForReconciliation() {
        UUID subscriptionId = UUID.randomUUID();
        ActivateSubscriptionRequest request =
                new ActivateSubscriptionRequest(ADMIN_ID, USER_ID, "MONTH_1");
        ActivationPreparation preparation = new ActivationPreparation(
                subscriptionId, USER_ID, NOW.plusSeconds(3600),
                ActivationPreparation.Type.PROVISION, null);
        when(transactionService.prepareActivation(request, NOW)).thenReturn(preparation);
        doThrow(new ru.murad.myvpn.exception.ThreeXUiUncertainException())
                .when(vpnProvider).provision(any());

        assertThatThrownBy(() -> service.activate(request))
                .isInstanceOf(ru.murad.myvpn.exception
                        .ThreeXUiUncertainException.class);

        verify(transactionService)
                .markProvisionReconciliationRequired(subscriptionId, NOW);
        verify(transactionService, never()).markProvisionFailed(subscriptionId, NOW);
    }

    @Test
    void shouldLeavePendingForRecoveryWhenConfirmedProvisionCannotBePersisted() {
        UUID subscriptionId = UUID.randomUUID();
        ActivateSubscriptionRequest request =
                new ActivateSubscriptionRequest(ADMIN_ID, USER_ID, "MONTH_1");
        ActivationPreparation preparation = new ActivationPreparation(
                subscriptionId, USER_ID, NOW.plusSeconds(3600),
                ActivationPreparation.Type.PROVISION, null);
        ProvisionedVpnAccess provisioned =
                new ProvisionedVpnAccess("FAKE", "external", null);
        when(transactionService.prepareActivation(request, NOW)).thenReturn(preparation);
        when(vpnProvider.provision(any())).thenReturn(provisioned);
        doThrow(new IllegalStateException("database failure"))
                .when(transactionService)
                .completeProvision(subscriptionId, provisioned, NOW);

        assertThatThrownBy(() -> service.activate(request))
                .isInstanceOf(IllegalStateException.class);

        verify(transactionService, never()).markProvisionFailed(subscriptionId, NOW);
    }

    @Test
    void shouldExtendAndPersistOnlyAfterProviderConfirmation() {
        UUID subscriptionId = UUID.randomUUID();
        Instant expiry = NOW.plusSeconds(7200);
        ActivateSubscriptionRequest request =
                new ActivateSubscriptionRequest(ADMIN_ID, USER_ID, "MONTH_3");
        ActivationPreparation preparation = new ActivationPreparation(
                subscriptionId, USER_ID, expiry,
                ActivationPreparation.Type.EXTEND, "external");
        when(transactionService.prepareActivation(request, NOW)).thenReturn(preparation);

        service.activate(request);

        verify(vpnProvider).extend(new VpnExtensionRequest("external", expiry));
        verify(transactionService).completeExtension(
                subscriptionId, "MONTH_3", ADMIN_ID, expiry, NOW);
    }

    @Test
    void shouldRejectUnauthorizedAdministratorBeforePreparingActivation() {
        ActivateSubscriptionRequest request =
                new ActivateSubscriptionRequest(ADMIN_ID, USER_ID, "MONTH_1");
        doThrow(new AdministratorAccessDeniedException())
                .when(authorizationService).checkAccess(ADMIN_ID);

        assertThatThrownBy(() -> service.activate(request))
                .isInstanceOf(AdministratorAccessDeniedException.class);

        verify(transactionService, never()).prepareActivation(request, NOW);
    }

    @Test
    void shouldRevokeProviderBeforeCommittingLocalState() {
        UUID subscriptionId = UUID.randomUUID();
        when(transactionService.findActiveAccess(USER_ID))
                .thenReturn(new VpnAccessCandidate(subscriptionId, "external"));

        service.revoke(new RevokeSubscriptionRequest(ADMIN_ID, USER_ID));

        verify(vpnProvider).revoke("external");
        verify(transactionService).completeRevocation(subscriptionId, NOW);
    }

    @Test
    void shouldNotCommitRevocationWhenProviderRejectsLastClient() {
        UUID subscriptionId = UUID.randomUUID();
        when(transactionService.findActiveAccess(USER_ID))
                .thenReturn(new VpnAccessCandidate(subscriptionId, "external"));
        doThrow(new ru.murad.myvpn.exception.ThreeXUiLastClientException())
                .when(vpnProvider).revoke("external");

        assertThatThrownBy(() -> service.revoke(
                new RevokeSubscriptionRequest(ADMIN_ID, USER_ID)))
                .isInstanceOf(ru.murad.myvpn.exception
                        .ThreeXUiLastClientException.class);

        verify(transactionService, never())
                .completeRevocation(subscriptionId, NOW);
    }
}
