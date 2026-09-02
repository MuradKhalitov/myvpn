package ru.murad.myvpn.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.murad.myvpn.client.threexui.ThreeXUiClientRequest;
import ru.murad.myvpn.client.threexui.ThreeXUiInboundResponse;
import ru.murad.myvpn.client.threexui.ThreeXUiInboundSettings;
import ru.murad.myvpn.client.threexui.ThreeXUiVlessClient;
import ru.murad.myvpn.config.ThreeXUiProperties;
import ru.murad.myvpn.exception.ThreeXUiException;
import ru.murad.myvpn.exception.ThreeXUiRetryableException;
import ru.murad.myvpn.exception.ThreeXUiUncertainException;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ThreeXUiVpnProviderTest {

    private static final UUID SUBSCRIPTION_ID =
            UUID.fromString("10000000-0000-0000-0000-000000000099");
    private static final Instant EXPIRY = Instant.parse("2026-08-24T10:00:00Z");

    @Mock private ThreeXUiInboundClient inboundClient;
    @Mock private VpnConfigurationFactory configurationFactory;
    @Mock private ThreeXUiConfigurationMapper configurationMapper;

    private ThreeXUiVpnProvider provider;

    @BeforeEach
    void setUp() {
        ThreeXUiProperties properties = new ThreeXUiProperties(
                URI.create("https://test.invalid"),
                "/test-path",
                "test-user",
                "test-password",
                42,
                "vpn.example.test",
                null,
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                3,
                16,
                Duration.ZERO,
                Duration.ZERO);
        provider = new ThreeXUiVpnProvider(
                inboundClient, configurationFactory, configurationMapper, properties);
    }

    @Test
    void shouldReturnExistingClientWithoutCreatingDuplicate() {
        ThreeXUiInboundResponse inbound = inbound();
        ThreeXUiVlessClient client = client(EXPIRY.toEpochMilli());
        when(inboundClient.getInbound(any())).thenReturn(inbound);
        when(inboundClient.parseSettings(inbound))
                .thenReturn(new ThreeXUiInboundSettings(List.of(client)));
        stubConfiguration(inbound);

        ProvisionedVpnAccess result = provider.provision(provisionRequest());

        assertThat(result.externalAccessId()).isEqualTo(SUBSCRIPTION_ID.toString());
        assertThat(result.configurationData()).isEqualTo("vless://generated");
        verify(inboundClient, never()).addClient(any(), any());
        verify(inboundClient, never()).updateClient(any(), any(), any());
    }

    @Test
    void shouldCreateAndConfirmClientUsingSubscriptionUuid() {
        ThreeXUiInboundResponse before = inbound("before");
        ThreeXUiInboundResponse after = inbound("after");
        ThreeXUiVlessClient client = client(EXPIRY.toEpochMilli());
        when(inboundClient.getInbound(any())).thenReturn(before);
        when(inboundClient.getInboundForReconciliation(any())).thenReturn(after);
        when(inboundClient.parseSettings(before))
                .thenReturn(new ThreeXUiInboundSettings(List.of()));
        when(inboundClient.parseSettings(after))
                .thenReturn(new ThreeXUiInboundSettings(List.of(client)));
        when(inboundClient.serializeSettings(any())).thenReturn("serialized-settings");
        stubConfiguration(after);

        provider.provision(provisionRequest());

        ArgumentCaptor<ThreeXUiClientRequest> captor =
                ArgumentCaptor.forClass(ThreeXUiClientRequest.class);
        verify(inboundClient).addClient(captor.capture(), any());
        assertThat(captor.getValue().id()).isEqualTo(42);
        assertThat(captor.getValue().settings()).isEqualTo("serialized-settings");
    }

    @Test
    void provisionTargetForMissingClientStartsFromNow() {
        ThreeXUiInboundResponse inbound = inbound("empty");
        Instant now = Instant.parse("2026-07-25T10:00:00.123456Z");
        when(inboundClient.getInbound(any())).thenReturn(inbound);
        when(inboundClient.parseSettings(inbound))
                .thenReturn(new ThreeXUiInboundSettings(List.of()));

        assertThat(provider.resolveProvisionTarget(provisionRequest(), 30, now))
                .isEqualTo(Instant.ofEpochMilli(now.plus(Duration.ofDays(30)).toEpochMilli()));
    }

    @Test
    void provisionTargetForExistingActiveClientAddsPurchasedDurationOnce() {
        ThreeXUiInboundResponse inbound = inbound("existing");
        Instant now = Instant.parse("2026-07-25T10:00:00Z");
        Instant existingExpiry = now.plus(Duration.ofDays(30));
        when(inboundClient.getInbound(any())).thenReturn(inbound);
        when(inboundClient.parseSettings(inbound)).thenReturn(new ThreeXUiInboundSettings(
                List.of(client(existingExpiry.toEpochMilli()))));

        assertThat(provider.resolveProvisionTarget(provisionRequest(), 30, now))
                .isEqualTo(existingExpiry.plus(Duration.ofDays(30)));
        verify(inboundClient, never()).addClient(any(), any());
        verify(inboundClient, never()).updateClient(any(), any(), any());
    }

    @Test
    void provisionTargetForExpiredClientStartsFromNow() {
        ThreeXUiInboundResponse inbound = inbound("expired");
        Instant now = Instant.parse("2026-07-25T10:00:00Z");
        when(inboundClient.getInbound(any())).thenReturn(inbound);
        when(inboundClient.parseSettings(inbound)).thenReturn(new ThreeXUiInboundSettings(
                List.of(client(now.minusSeconds(1).toEpochMilli()))));

        assertThat(provider.resolveProvisionTarget(provisionRequest(), 30, now))
                .isEqualTo(now.plus(Duration.ofDays(30)));
    }

    @Test
    void duplicateStableClientIdentityFailsBeforeMutation() {
        ThreeXUiInboundResponse inbound = inbound("duplicate");
        when(inboundClient.getInbound(any())).thenReturn(inbound);
        when(inboundClient.parseSettings(inbound)).thenReturn(new ThreeXUiInboundSettings(List.of(
                client(EXPIRY.toEpochMilli()), client(EXPIRY.toEpochMilli()))));

        assertThatThrownBy(() -> provider.resolveProvisionTarget(provisionRequest(), 30, EXPIRY))
                .isInstanceOf(ThreeXUiException.class);
        verify(inboundClient, never()).addClient(any(), any());
        verify(inboundClient, never()).updateClient(any(), any(), any());
    }

    @Test
    void shouldReconcileExistingClientWithStaleExpiryWithoutCreatingDuplicate() {
        ThreeXUiInboundResponse before = inbound("before");
        ThreeXUiInboundResponse after = inbound("after");
        ThreeXUiVlessClient stale = client(EXPIRY.minusSeconds(3600).toEpochMilli());
        ThreeXUiVlessClient reconciled = stale.withExpiryTime(EXPIRY.toEpochMilli());
        ThreeXUiClientRequest updateRequest =
                new ThreeXUiClientRequest(42, "reconcile-settings");
        when(inboundClient.getInbound(any())).thenReturn(before);
        when(inboundClient.getInboundForReconciliation(any())).thenReturn(after);
        when(inboundClient.parseSettings(before))
                .thenReturn(new ThreeXUiInboundSettings(List.of(stale)));
        when(inboundClient.parseSettings(after))
                .thenReturn(new ThreeXUiInboundSettings(List.of(reconciled)));
        when(inboundClient.prepareProvisionReconciliationRequest(
                before, SUBSCRIPTION_ID.toString(), EXPIRY.toEpochMilli()))
                .thenReturn(updateRequest);
        when(inboundClient.otherClientsUnchanged(
                before, after, SUBSCRIPTION_ID.toString())).thenReturn(true);
        stubConfiguration(after);

        ProvisionedVpnAccess result = provider.provision(provisionRequest());

        assertThat(result.targetExpiresAt()).isEqualTo(EXPIRY);
        verify(inboundClient).updateClient(
                org.mockito.ArgumentMatchers.eq(SUBSCRIPTION_ID.toString()),
                org.mockito.ArgumentMatchers.eq(updateRequest),
                org.mockito.ArgumentMatchers.any());
        verify(inboundClient, never()).addClient(any(), any());
    }

    @Test
    void repeatedProvisionWithConfirmedStateIsIdempotent() {
        ThreeXUiInboundResponse inbound = inbound("confirmed");
        ThreeXUiVlessClient confirmed = client(EXPIRY.toEpochMilli());
        when(inboundClient.getInbound(any())).thenReturn(inbound);
        when(inboundClient.parseSettings(inbound))
                .thenReturn(new ThreeXUiInboundSettings(List.of(confirmed)));
        stubConfiguration(inbound);

        provider.provision(provisionRequest());
        provider.provision(provisionRequest());

        verify(inboundClient, never()).addClient(any(), any());
        verify(inboundClient, never()).updateClient(any(), any(), any());
    }

    @Test
    void provisionUpdateTimeoutWithStaleReconciliationIsUncertain() {
        ThreeXUiInboundResponse staleInbound = inbound("stale");
        ThreeXUiVlessClient stale = client(EXPIRY.minusSeconds(3600).toEpochMilli());
        when(inboundClient.getInbound(any())).thenReturn(staleInbound);
        when(inboundClient.getInboundForReconciliation(any())).thenReturn(staleInbound);
        when(inboundClient.parseSettings(staleInbound))
                .thenReturn(new ThreeXUiInboundSettings(List.of(stale)));
        when(inboundClient.prepareProvisionReconciliationRequest(
                staleInbound, SUBSCRIPTION_ID.toString(), EXPIRY.toEpochMilli()))
                .thenReturn(new ThreeXUiClientRequest(42, "reconcile-settings"));
        doThrow(new ThreeXUiRetryableException("temporary"))
                .when(inboundClient).updateClient(any(), any(), any());

        assertThatThrownBy(() -> provider.provision(provisionRequest()))
                .isInstanceOf(ThreeXUiUncertainException.class);

        verify(inboundClient, times(3)).updateClient(any(), any(), any());
        verify(inboundClient, never()).addClient(any(), any());
    }

    @Test
    void shouldRejectReconciliationThatChangesAnotherClient() {
        ThreeXUiInboundResponse before = inbound("before");
        ThreeXUiInboundResponse after = inbound("after");
        ThreeXUiVlessClient stale = client(EXPIRY.minusSeconds(3600).toEpochMilli());
        ThreeXUiVlessClient reconciled = stale.withExpiryTime(EXPIRY.toEpochMilli());
        when(inboundClient.getInbound(any())).thenReturn(before);
        when(inboundClient.getInboundForReconciliation(any())).thenReturn(after);
        when(inboundClient.parseSettings(before))
                .thenReturn(new ThreeXUiInboundSettings(List.of(stale)));
        when(inboundClient.parseSettings(after))
                .thenReturn(new ThreeXUiInboundSettings(List.of(reconciled)));
        when(inboundClient.prepareProvisionReconciliationRequest(
                before, SUBSCRIPTION_ID.toString(), EXPIRY.toEpochMilli()))
                .thenReturn(new ThreeXUiClientRequest(42, "reconcile-settings"));
        when(inboundClient.otherClientsUnchanged(
                before, after, SUBSCRIPTION_ID.toString())).thenReturn(false);

        assertThatThrownBy(() -> provider.provision(provisionRequest()))
                .isInstanceOf(ThreeXUiUncertainException.class);

        verify(inboundClient, never()).addClient(any(), any());
    }

    @Test
    void shouldRecoverWhenCreateTimesOutButClientExists() {
        ThreeXUiInboundResponse before = inbound("before");
        ThreeXUiInboundResponse recovered = inbound("recovered");
        ThreeXUiVlessClient client = client(EXPIRY.toEpochMilli());
        when(inboundClient.getInbound(any())).thenReturn(before);
        when(inboundClient.getInboundForReconciliation(any())).thenReturn(recovered);
        when(inboundClient.parseSettings(before))
                .thenReturn(new ThreeXUiInboundSettings(List.of()));
        when(inboundClient.parseSettings(recovered))
                .thenReturn(new ThreeXUiInboundSettings(List.of(client)));
        when(inboundClient.serializeSettings(any())).thenReturn("settings");
        stubConfiguration(recovered);
        doThrow(new ThreeXUiRetryableException("temporary"))
                .when(inboundClient).addClient(any(), any());

        assertThat(provider.provision(provisionRequest()).externalAccessId())
                .isEqualTo(SUBSCRIPTION_ID.toString());
        verify(inboundClient, times(1)).addClient(any(), any());
    }

    @Test
    void shouldFailAfterCreateCannotBeConfirmed() {
        ThreeXUiInboundResponse inbound = inbound();
        when(inboundClient.getInbound(any())).thenReturn(inbound);
        when(inboundClient.getInboundForReconciliation(any())).thenReturn(inbound);
        when(inboundClient.parseSettings(inbound))
                .thenReturn(new ThreeXUiInboundSettings(List.of()));
        when(inboundClient.serializeSettings(any())).thenReturn("settings");

        assertThatThrownBy(() -> provider.provision(provisionRequest()))
                .isInstanceOf(ThreeXUiException.class)
                .hasMessage("3x-ui client creation was not confirmed");

        verify(inboundClient, times(3)).addClient(any(), any());
    }

    @Test
    void shouldUpdateOnlyExpiryAndConfirmIt() {
        ThreeXUiInboundResponse before = inbound("before");
        ThreeXUiInboundResponse after = inbound("after");
        ThreeXUiVlessClient existing = new ThreeXUiVlessClient(
                SUBSCRIPTION_ID.toString(), "preserved-security", null,
                "preserved-flow", null, "preserved-email", 2, 1000L,
                100L, true, 0L, "preserved-sub-id", "preserved-comment",
                30, 10L, 20L);
        ThreeXUiVlessClient updated = existing.withExpiryTime(EXPIRY.toEpochMilli());
        ThreeXUiClientRequest updateRequest =
                new ThreeXUiClientRequest(42, "target-only-settings");
        when(inboundClient.getInbound(any())).thenReturn(before);
        when(inboundClient.getInboundForReconciliation(any())).thenReturn(after);
        when(inboundClient.parseSettings(before))
                .thenReturn(new ThreeXUiInboundSettings(List.of(existing)));
        when(inboundClient.parseSettings(after))
                .thenReturn(new ThreeXUiInboundSettings(List.of(updated)));
        when(inboundClient.prepareExpiryUpdateRequest(
                before, SUBSCRIPTION_ID.toString(), EXPIRY.toEpochMilli()))
                .thenReturn(updateRequest);
        when(inboundClient.otherClientsUnchanged(
                before, after, SUBSCRIPTION_ID.toString()))
                .thenReturn(true);

        provider.extend(new VpnExtensionRequest(
                SUBSCRIPTION_ID.toString(), EXPIRY));

        verify(inboundClient).updateClient(
                org.mockito.ArgumentMatchers.eq(SUBSCRIPTION_ID.toString()),
                org.mockito.ArgumentMatchers.eq(updateRequest),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void extendReturnsConfirmedIdentityAndAbsoluteExpiry() {
        ThreeXUiInboundResponse before = inbound("before");
        ThreeXUiInboundResponse after = inbound("after");
        ThreeXUiVlessClient existing = client(1000L);
        ThreeXUiVlessClient updated = existing.withExpiryTime(EXPIRY.toEpochMilli());
        when(inboundClient.getInbound(any())).thenReturn(before);
        when(inboundClient.getInboundForReconciliation(any())).thenReturn(after);
        when(inboundClient.parseSettings(before)).thenReturn(new ThreeXUiInboundSettings(List.of(existing)));
        when(inboundClient.parseSettings(after)).thenReturn(new ThreeXUiInboundSettings(List.of(updated)));
        when(inboundClient.prepareExpiryUpdateRequest(before, SUBSCRIPTION_ID.toString(), EXPIRY.toEpochMilli()))
                .thenReturn(new ThreeXUiClientRequest(42, "expiry-only"));
        when(inboundClient.otherClientsUnchanged(before, after, SUBSCRIPTION_ID.toString())).thenReturn(true);

        ProvisionedVpnAccess result = provider.extend(new VpnExtensionRequest(SUBSCRIPTION_ID.toString(), EXPIRY));
        assertThat(result.externalAccessId()).isEqualTo(SUBSCRIPTION_ID.toString());
        assertThat(result.targetExpiresAt()).isEqualTo(EXPIRY);
    }

    @Test
    void extendWithAlreadyAppliedTargetIsIdempotent() {
        ThreeXUiInboundResponse inbound = inbound("same-target");
        when(inboundClient.getInbound(any())).thenReturn(inbound);
        when(inboundClient.parseSettings(inbound))
                .thenReturn(new ThreeXUiInboundSettings(List.of(client(EXPIRY.toEpochMilli()))));

        ProvisionedVpnAccess result = provider.extend(new VpnExtensionRequest(SUBSCRIPTION_ID.toString(), EXPIRY));
        assertThat(result.targetExpiresAt()).isEqualTo(EXPIRY);
        verify(inboundClient, never()).updateClient(any(), any(), any());
    }

    @Test
    void provisionUsesStableExternalAccessIdWhenProvided() {
        ThreeXUiInboundResponse inbound = inbound("stable");
        when(inboundClient.getInbound(any())).thenReturn(inbound);
        when(inboundClient.parseSettings(inbound)).thenReturn(new ThreeXUiInboundSettings(List.of(
                ThreeXUiVlessClient.create("stable-client", "stable-email", EXPIRY.toEpochMilli()))));
        stubConfiguration(inbound, "stable-client");

        ProvisionedVpnAccess result = provider.provision(new VpnProvisionRequest(
                SUBSCRIPTION_ID, 123L, EXPIRY, "stable-client"));
        assertThat(result.externalAccessId()).isEqualTo("stable-client");
    }

    @Test
    void extendMissingIdentityIsRejectedWithoutMutation() {
        ThreeXUiInboundResponse inbound = inbound("missing");
        when(inboundClient.getInbound(any())).thenReturn(inbound);
        when(inboundClient.parseSettings(inbound)).thenReturn(new ThreeXUiInboundSettings(List.of()));
        assertThatThrownBy(() -> provider.extend(new VpnExtensionRequest("missing", EXPIRY)))
                .isInstanceOf(ru.murad.myvpn.exception.ThreeXUiNotFoundException.class);
        verify(inboundClient, never()).updateClient(any(), any(), any());
    }

    @Test
    void providerResultRenderingDoesNotExposeConfiguration() {
        ThreeXUiInboundResponse inbound = inbound();
        when(inboundClient.getInbound(any())).thenReturn(inbound);
        when(inboundClient.parseSettings(inbound)).thenReturn(new ThreeXUiInboundSettings(List.of(client(EXPIRY.toEpochMilli()))));
        stubConfiguration(inbound);
        assertThat(provider.provision(provisionRequest()).toString()).doesNotContain("vless://generated");
    }

    @Test
    void freePolicyReusesTheExistingClientAndStableResetKey() {
        ThreeXUiInboundResponse before = inbound("before");
        ThreeXUiInboundResponse after = inbound("after");
        ThreeXUiVlessClient existing = client(EXPIRY.toEpochMilli());
        ThreeXUiVlessClient limited = new ThreeXUiVlessClient(existing.id(), existing.security(),
                existing.password(), existing.flow(), existing.auth(), existing.email(), existing.limitIp(),
                100L, existing.expiryTime(), existing.enable(), existing.tgId(), existing.subId(),
                existing.comment(), existing.reset(), existing.createdAt(), existing.updatedAt());
        ThreeXUiClientRequest update = new ThreeXUiClientRequest(42, "free-policy");
        when(inboundClient.getInbound(any())).thenReturn(before);
        when(inboundClient.getInboundForReconciliation(any())).thenReturn(after);
        when(inboundClient.parseSettings(before)).thenReturn(new ThreeXUiInboundSettings(List.of(existing)));
        when(inboundClient.parseSettings(after)).thenReturn(new ThreeXUiInboundSettings(List.of(limited)));
        when(inboundClient.prepareTrafficPolicyUpdateRequest(before, SUBSCRIPTION_ID.toString(), 100L))
                .thenReturn(update);
        when(inboundClient.otherClientsUnchanged(before, after, SUBSCRIPTION_ID.toString())).thenReturn(true);

        provider.applyTrafficPolicy(SUBSCRIPTION_ID.toString(), "stable-reset-key", VpnTrafficPolicy.limited(100L));

        verify(inboundClient).updateClient(org.mockito.ArgumentMatchers.eq(SUBSCRIPTION_ID.toString()),
                org.mockito.ArgumentMatchers.eq(update), org.mockito.ArgumentMatchers.any());
        verify(inboundClient).resetClientTraffic(org.mockito.ArgumentMatchers.eq("stable-reset-key"),
                org.mockito.ArgumentMatchers.any());
        verify(inboundClient, never()).deleteClient(any(), any());
    }

    @Test
    void shouldTreatMissingClientAsAlreadyRevoked() {
        ThreeXUiInboundResponse inbound = inbound();
        when(inboundClient.getInbound(any())).thenReturn(inbound);
        when(inboundClient.parseSettings(inbound))
                .thenReturn(new ThreeXUiInboundSettings(List.of()));

        provider.revoke(SUBSCRIPTION_ID.toString());

        verify(inboundClient, never()).deleteClient(any(), any());
    }

    @Test
    void shouldRejectRevokingLastInboundClient() {
        ThreeXUiInboundResponse inbound = inbound();
        when(inboundClient.getInbound(any())).thenReturn(inbound);
        when(inboundClient.parseSettings(inbound))
                .thenReturn(new ThreeXUiInboundSettings(
                        List.of(client(EXPIRY.toEpochMilli()))));

        assertThatThrownBy(() -> provider.revoke(SUBSCRIPTION_ID.toString()))
                .isInstanceOf(ru.murad.myvpn.exception
                        .ThreeXUiLastClientException.class);

        verify(inboundClient, never()).deleteClient(any(), any());
    }

    @Test
    void shouldRecoverWhenRevokeTimesOutAndClientIsAbsent() {
        ThreeXUiInboundResponse before = inbound("before");
        ThreeXUiInboundResponse after = inbound("after");
        when(inboundClient.getInbound(any())).thenReturn(before);
        when(inboundClient.getInboundForReconciliation(any())).thenReturn(after);
        when(inboundClient.parseSettings(before))
                .thenReturn(new ThreeXUiInboundSettings(
                        List.of(client(EXPIRY.toEpochMilli()),
                                ThreeXUiVlessClient.create(
                                        "service-client", "service-email", 0))));
        when(inboundClient.parseSettings(after))
                .thenReturn(new ThreeXUiInboundSettings(List.of()));
        doThrow(new ThreeXUiRetryableException("temporary"))
                .when(inboundClient).deleteClient(
                        org.mockito.ArgumentMatchers.eq(SUBSCRIPTION_ID.toString()), any());

        provider.revoke(SUBSCRIPTION_ID.toString());

        verify(inboundClient, times(1)).deleteClient(
                org.mockito.ArgumentMatchers.eq(SUBSCRIPTION_ID.toString()), any());
    }

    private VpnProvisionRequest provisionRequest() {
        return new VpnProvisionRequest(SUBSCRIPTION_ID, 123L, EXPIRY,
                SUBSCRIPTION_ID.toString());
    }

    private ThreeXUiInboundResponse inbound() {
        return new ThreeXUiInboundResponse(42, 443, "vless", "settings", "stream");
    }

    private ThreeXUiInboundResponse inbound(String marker) {
        return new ThreeXUiInboundResponse(42, 443, "vless", marker, "stream");
    }

    private ThreeXUiVlessClient client(long expiryTime) {
        return ThreeXUiVlessClient.create(
                SUBSCRIPTION_ID.toString(), "test-email", expiryTime);
    }

    private void stubConfiguration(ThreeXUiInboundResponse inbound) {
        stubConfiguration(inbound, SUBSCRIPTION_ID.toString());
    }

    private void stubConfiguration(ThreeXUiInboundResponse inbound, String clientId) {
        VlessConfigurationData data = new VlessConfigurationData(
                clientId, "vpn.example.test", 443,
                "tcp", "reality", "none", "", "server.example",
                "chrome", "public-key", "abcd", "/", "MyVPN");
        when(configurationMapper.map(inbound, clientId))
                .thenReturn(data);
        when(configurationFactory.create(data)).thenReturn("vless://generated");
    }
}
