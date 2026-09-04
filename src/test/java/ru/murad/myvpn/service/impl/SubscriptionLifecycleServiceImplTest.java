package ru.murad.myvpn.service.impl;

import org.junit.jupiter.api.Test;
import ru.murad.myvpn.config.VpnTrafficProperties;
import ru.murad.myvpn.model.Account;
import ru.murad.myvpn.model.Subscription;
import ru.murad.myvpn.model.SubscriptionStatus;
import ru.murad.myvpn.model.VpnAccess;
import ru.murad.myvpn.model.VpnAccessStatus;
import ru.murad.myvpn.model.VpnEntitlement;
import ru.murad.myvpn.model.VpnPolicyStatus;
import ru.murad.myvpn.repository.SubscriptionRepository;
import ru.murad.myvpn.repository.VpnAccessRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SubscriptionLifecycleServiceImplTest {
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");

    @Test
    void expiredPremiumDowngradesCanonicalAccessToFreeWithoutRevokingProviderIdentity() {
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
        ru.murad.myvpn.repository.AccountRepository accounts = mock(ru.murad.myvpn.repository.AccountRepository.class);
        VpnAccessRepository accesses = mock(VpnAccessRepository.class);
        VpnTrafficPolicyTransactionService policies = mock(VpnTrafficPolicyTransactionService.class);
        SubscriptionLifecycleServiceImpl service = new SubscriptionLifecycleServiceImpl(subscriptions, accounts, accesses,
                policies, new VpnTrafficProperties(100L, 30, 0), Clock.fixed(NOW, ZoneOffset.UTC));
        UUID accountId = UUID.randomUUID();
        Account account = Account.builder().id(accountId).createdAt(NOW).updatedAt(NOW).build();
        Subscription subscription = Subscription.builder().id(UUID.randomUUID()).account(account)
                .status(SubscriptionStatus.ACTIVE).startsAt(NOW.minusSeconds(3600)).expiresAt(NOW)
                .activatedAt(NOW.minusSeconds(3600)).createdAt(NOW.minusSeconds(3600)).updatedAt(NOW).build();
        VpnAccess access = VpnAccess.builder().id(UUID.randomUUID()).account(account).subscription(subscription)
                .providerName("FAKE").externalAccessId(accountId.toString()).providerClientKey("acc_" + accountId)
                .configurationData("redacted").status(VpnAccessStatus.ACTIVE).issuedAt(NOW.minusSeconds(3600))
                .createdAt(NOW.minusSeconds(3600)).updatedAt(NOW).desiredEntitlement(VpnEntitlement.PREMIUM)
                .appliedEntitlement(VpnEntitlement.PREMIUM).policyStatus(VpnPolicyStatus.APPLIED).build();
        when(subscriptions.findAllByStatusAndExpiresAtLessThanEqual(SubscriptionStatus.ACTIVE, NOW))
                .thenReturn(List.of(subscription), List.of());
        when(accesses.findBySubscriptionId(subscription.getId())).thenReturn(Optional.of(access));

        assertThat(service.revokeExpiredSubscriptions()).isEqualTo(1);
        assertThat(service.revokeExpiredSubscriptions()).isZero();

        verify(policies).request(accountId, VpnEntitlement.EXPIRED, null, null, NOW);
        verify(subscriptions).save(subscription);
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
        assertThat(access.getStatus()).isEqualTo(VpnAccessStatus.ACTIVE);
        assertThat(access.getExternalAccessId()).isEqualTo(accountId.toString());
        assertThat(access.getProviderClientKey()).isEqualTo("acc_" + accountId);
        assertThat(access.getConfigurationData()).isEqualTo("redacted");
    }
}
