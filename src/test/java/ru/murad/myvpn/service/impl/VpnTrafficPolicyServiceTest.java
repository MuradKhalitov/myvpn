package ru.murad.myvpn.service.impl;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import ru.murad.myvpn.client.VpnProvider;
import ru.murad.myvpn.client.ProvisionedVpnAccess;
import ru.murad.myvpn.client.VpnProvisionRequest;
import ru.murad.myvpn.client.VpnTrafficPolicy;
import ru.murad.myvpn.config.VpnTrafficProperties;
import ru.murad.myvpn.model.VpnEntitlement;
import ru.murad.myvpn.service.VpnTrafficPolicyCandidate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VpnTrafficPolicyServiceTest {
    private final Instant now = Instant.parse("2026-08-31T00:00:00Z");
    private final VpnTrafficPolicyTransactionService transactions = mock(VpnTrafficPolicyTransactionService.class);
    private final AccountVpnAccessTransactionService freeTransactions = mock(AccountVpnAccessTransactionService.class);
    private final VpnProvider provider = mock(VpnProvider.class);
    private final VpnTrafficPolicyServiceImpl service = new VpnTrafficPolicyServiceImpl(transactions, freeTransactions, provider,
            new VpnTrafficProperties(5L * 1024 * 1024 * 1024, 30, 0), Clock.fixed(now, ZoneOffset.UTC));

    @Test void appliesConfiguredFreeBytesAndCompletesOnlyAfterProviderConfirmation() {
        VpnTrafficPolicyCandidate c = candidate(VpnEntitlement.FREE, 3); when(transactions.due(now)).thenReturn(List.of(c));
        when(transactions.complete(c.accessId(), 3, now)).thenReturn(true);
        service.reconcileDuePolicies();
        verify(provider).applyTrafficPolicy(eq("external"), eq("acc_key"), eq(VpnTrafficPolicy.limited(5L * 1024 * 1024 * 1024)));
        verify(transactions).complete(c.accessId(), 3, now); verify(transactions, never()).retry(any(), anyLong(), any());
    }

    @Test void appliesExplicitPremiumProviderValueWithoutClaimingItsServerSemantics() {
        VpnTrafficPolicyCandidate c = candidate(VpnEntitlement.PREMIUM, 4); when(transactions.due(now)).thenReturn(List.of(c));
        when(transactions.complete(c.accessId(), 4, now)).thenReturn(true);
        service.reconcileDuePolicies();
        verify(provider).applyTrafficPolicy("external", "acc_key", VpnTrafficPolicy.unlimited(0));
    }

    @Test void providerFailureSchedulesRetryAndDoesNotMarkApplied() {
        VpnTrafficPolicyCandidate c = candidate(VpnEntitlement.FREE, 2); when(transactions.due(now)).thenReturn(List.of(c));
        doThrow(new RuntimeException("timeout")).when(provider).applyTrafficPolicy(any(), any(), any());
        when(transactions.retry(c.accessId(), 2, now)).thenReturn(true);
        service.reconcileDuePolicies();
        verify(transactions, never()).complete(any(), anyLong(), any()); verify(transactions).retry(c.accessId(), 2, now);
    }

    @Test void staleGenerationCannotOverwriteNewerDesiredPolicy() {
        VpnTrafficPolicyCandidate c = candidate(VpnEntitlement.FREE, 3); when(transactions.due(now)).thenReturn(List.of(c));
        when(transactions.complete(c.accessId(), 3, now)).thenReturn(false);
        service.reconcileDuePolicies();
        verify(transactions).complete(c.accessId(), 3, now);
    }

    @Test void repeatedSameCandidateIsIdempotentAtProviderPort() {
        VpnTrafficPolicyCandidate c = candidate(VpnEntitlement.FREE, 5); when(transactions.due(now)).thenReturn(List.of(c));
        when(transactions.complete(c.accessId(), 5, now)).thenReturn(true);
        service.reconcileDuePolicies(); service.reconcileDuePolicies();
        verify(provider, times(2)).applyTrafficPolicy("external", "acc_key", VpnTrafficPolicy.limited(5L * 1024 * 1024 * 1024));
    }

    @Test void retriesInitialProvisioningWithStableIdentityAndCompletesFreeAccess() {
        UUID accountId = UUID.randomUUID();
        VpnTrafficPolicyCandidate c = provisioningCandidate(accountId, 7);
        when(transactions.due(now)).thenReturn(List.of(c));
        when(provider.provision(new VpnProvisionRequest(accountId, 0L, c.provisioningExpiresAt(), "external", "acc_key")))
                .thenReturn(new ProvisionedVpnAccess("FAKE", "external", "config"));
        when(freeTransactions.completeFree(c.accessId(), 7, "FAKE", "config", now)).thenReturn(true);

        service.reconcileDuePolicies();

        InOrder order = inOrder(provider, freeTransactions);
        order.verify(provider).provision(new VpnProvisionRequest(accountId, 0L, c.provisioningExpiresAt(), "external", "acc_key"));
        order.verify(provider).applyTrafficPolicy("external", "acc_key", VpnTrafficPolicy.limited(5L * 1024 * 1024 * 1024));
        order.verify(freeTransactions).completeFree(c.accessId(), 7, "FAKE", "config", now);
        verify(transactions, never()).complete(c.accessId(), 7, now);
    }

    @Test void initialProvisioningFailureSchedulesRetryWithoutCompleting() {
        VpnTrafficPolicyCandidate c = provisioningCandidate(UUID.randomUUID(), 8);
        when(transactions.due(now)).thenReturn(List.of(c));
        doThrow(new RuntimeException("timeout")).when(provider).provision(any());
        when(transactions.retry(c.accessId(), 8, now)).thenReturn(true);

        service.reconcileDuePolicies();

        verify(provider, never()).applyTrafficPolicy(any(), any(), any());
        verify(freeTransactions, never()).completeFree(any(), anyLong(), any(), any(), any());
        verify(transactions).retry(c.accessId(), 8, now);
    }

    @Test void staleInitialProvisioningResultCannotOverwriteNewerGeneration() {
        VpnTrafficPolicyCandidate c = provisioningCandidate(UUID.randomUUID(), 9);
        when(transactions.due(now)).thenReturn(List.of(c));
        when(provider.provision(any())).thenReturn(new ProvisionedVpnAccess("FAKE", "external", "config"));
        when(freeTransactions.completeFree(c.accessId(), 9, "FAKE", "config", now)).thenReturn(false);

        service.reconcileDuePolicies();

        verify(freeTransactions).completeFree(c.accessId(), 9, "FAKE", "config", now);
        verify(transactions, never()).retry(any(), anyLong(), any());
    }

    private VpnTrafficPolicyCandidate candidate(VpnEntitlement entitlement, long generation) {
        return new VpnTrafficPolicyCandidate(UUID.randomUUID(), "external", "acc_key", entitlement, generation);
    }

    private VpnTrafficPolicyCandidate provisioningCandidate(UUID accountId, long generation) {
        return new VpnTrafficPolicyCandidate(UUID.randomUUID(), accountId, "external", "acc_key",
                VpnEntitlement.FREE, generation, now.plusSeconds(100), true);
    }
}
