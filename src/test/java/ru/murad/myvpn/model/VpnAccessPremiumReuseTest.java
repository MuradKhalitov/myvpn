package ru.murad.myvpn.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VpnAccessPremiumReuseTest {
    @Test
    void existingFreeAccessKeepsProviderIdentityWhenPremiumIsAttached() {
        Instant now = Instant.parse("2026-07-25T10:00:00Z");
        UUID accountId = UUID.randomUUID();
        String externalId = accountId.toString();
        String providerKey = "client-" + accountId;
        Account account = Account.builder().id(accountId).createdAt(now).updatedAt(now).build();
        VpnAccess access = VpnAccess.builder().id(UUID.randomUUID()).account(account)
                .providerName("FAKE").externalAccessId(externalId).providerClientKey(providerKey)
                .status(VpnAccessStatus.ACTIVE).createdAt(now).updatedAt(now).build();
        Subscription subscription = Subscription.builder().id(UUID.randomUUID()).account(account)
                .status(SubscriptionStatus.ACTIVE).startsAt(now).expiresAt(now.plusSeconds(3600))
                .createdAt(now).updatedAt(now).build();
        UUID canonicalId = access.getId();

        access.attachSubscription(subscription, now.plusSeconds(1));
        access.requestPolicy(VpnEntitlement.PREMIUM, now.plusSeconds(1), subscription.getExpiresAt(), now.plusSeconds(1));

        assertThat(access.getId()).isEqualTo(canonicalId);
        assertThat(access.getExternalAccessId()).isEqualTo(externalId);
        assertThat(access.getProviderClientKey()).isEqualTo(providerKey);
        assertThat(access.getSubscription()).isSameAs(subscription);
        assertThat(access.getDesiredEntitlement()).isEqualTo(VpnEntitlement.PREMIUM);
    }

    @Test
    void expiredPremiumCanReturnToFreeWithoutChangingCanonicalProviderIdentity() {
        Instant now = Instant.parse("2026-07-25T10:00:00Z");
        UUID accountId = UUID.randomUUID();
        Account account = Account.builder().id(accountId).createdAt(now).updatedAt(now).build();
        String externalId = accountId.toString();
        String providerKey = "client-" + accountId;
        VpnAccess access = VpnAccess.builder().id(UUID.randomUUID()).account(account)
                .providerName("FAKE").externalAccessId(externalId).providerClientKey(providerKey)
                .configurationData("redacted").status(VpnAccessStatus.ACTIVE).createdAt(now).updatedAt(now)
                .desiredEntitlement(VpnEntitlement.PREMIUM).appliedEntitlement(VpnEntitlement.PREMIUM)
                .policyStatus(VpnPolicyStatus.APPLIED).policyGeneration(2).build();

        access.requestPolicy(VpnEntitlement.FREE, now, now.plusSeconds(30L * 24 * 60 * 60), now);

        assertThat(access.getExternalAccessId()).isEqualTo(externalId);
        assertThat(access.getProviderClientKey()).isEqualTo(providerKey);
        assertThat(access.getConfigurationData()).isEqualTo("redacted");
        assertThat(access.getStatus()).isEqualTo(VpnAccessStatus.ACTIVE);
        assertThat(access.getDesiredEntitlement()).isEqualTo(VpnEntitlement.FREE);
        assertThat(access.getPolicyStatus()).isEqualTo(VpnPolicyStatus.PENDING);
        assertThat(access.getPolicyGeneration()).isEqualTo(3);
    }
}
