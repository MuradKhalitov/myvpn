package ru.murad.myvpn.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import ru.murad.myvpn.client.VpnProvider;
import ru.murad.myvpn.model.*;
import ru.murad.myvpn.service.SubscriptionLifecycleService;
import ru.murad.myvpn.service.VpnTrafficPolicyService;
import ru.murad.myvpn.support.AuthIntegrationTestSupport;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TrialLifecycleIntegrationTest extends AuthIntegrationTestSupport {
    @MockBean VpnProvider provider;
    @Autowired SubscriptionLifecycleService lifecycle;
    @Autowired VpnTrafficPolicyService policies;

    @Test
    void expiredTrialIsDurablyDisabledAndRepeatedCatchUpIsIdempotent() {
        Account account = account(Instant.now().minusSeconds(6 * 86400L));
        VpnAccess access = activeTrialAccess(account);
        when(provider.providerName()).thenReturn("FAKE");

        assertThat(lifecycle.expireTrials()).isEqualTo(1);
        assertThat(policies.reconcileDuePolicies()).isEqualTo(1);

        VpnAccess persisted = vpnAccessRepository.findById(access.getId()).orElseThrow();
        assertThat(persisted.getDesiredEntitlement()).isEqualTo(VpnEntitlement.EXPIRED);
        assertThat(persisted.getAppliedEntitlement()).isEqualTo(VpnEntitlement.EXPIRED);
        verify(provider).setAccessEnabled(access.getExternalAccessId(), false);

        assertThat(lifecycle.expireTrials()).isEqualTo(1);
        assertThat(policies.reconcileDuePolicies()).isZero();
        verify(provider, times(1)).setAccessEnabled(access.getExternalAccessId(), false);
    }

    @Test
    void activePremiumIsNotDisabledByExpiredTrialCatchUp() {
        Account account = account(Instant.now().minusSeconds(6 * 86400L));
        VpnAccess access = activeTrialAccess(account);
        subscription(account, Instant.now().plusSeconds(3600));

        assertThat(lifecycle.expireTrials()).isEqualTo(1);
        assertThat(vpnAccessRepository.findById(access.getId()).orElseThrow().getDesiredEntitlement())
                .isEqualTo(VpnEntitlement.TRIAL);
        verifyNoInteractions(provider);
    }

    private Account account(Instant trialStart) {
        Account account = Account.builder().id(UUID.randomUUID()).status(AccountStatus.ACTIVE)
                .createdAt(trialStart).updatedAt(trialStart).build();
        account.grantTrial(trialStart, trialStart.plusSeconds(5 * 86400L));
        return accountRepository.save(account);
    }
    private VpnAccess activeTrialAccess(Account account) {
        Instant now = Instant.now();
        return vpnAccessRepository.save(VpnAccess.builder().id(UUID.randomUUID()).account(account).providerName("FAKE")
                .externalAccessId(account.getId().toString()).providerClientKey("acc_" + account.getId())
                .configurationData("fake-vpn").status(VpnAccessStatus.ACTIVE).issuedAt(now).createdAt(now).updatedAt(now)
                .desiredEntitlement(VpnEntitlement.TRIAL).appliedEntitlement(VpnEntitlement.TRIAL)
                .policyStatus(VpnPolicyStatus.APPLIED).policyGeneration(1).build());
    }
    private void subscription(Account account, Instant expires) {
        subscriptions().save(Subscription.builder().id(UUID.randomUUID()).account(account)
                .tariff(vpnTariff()).status(SubscriptionStatus.ACTIVE).startsAt(Instant.now()).expiresAt(expires)
                .activatedAt(Instant.now()).createdAt(Instant.now()).updatedAt(Instant.now()).build());
    }
    @Autowired private ru.murad.myvpn.repository.SubscriptionRepository subscriptionRepository;
    @Autowired private ru.murad.myvpn.repository.VpnTariffRepository tariffRepository;
    private ru.murad.myvpn.repository.SubscriptionRepository subscriptions() { return subscriptionRepository; }
    private VpnTariff vpnTariff() { return tariffRepository.findAll().get(0); }
}
