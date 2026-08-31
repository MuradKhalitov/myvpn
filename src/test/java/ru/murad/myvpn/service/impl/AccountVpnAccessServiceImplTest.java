package ru.murad.myvpn.service.impl;

import org.junit.jupiter.api.Test;
import ru.murad.myvpn.client.ProvisionedVpnAccess;
import ru.murad.myvpn.client.VpnProvider;
import ru.murad.myvpn.client.VpnTrafficPolicy;
import ru.murad.myvpn.config.VpnTrafficProperties;
import ru.murad.myvpn.model.VpnAccess;
import ru.murad.myvpn.model.VpnAccessStatus;
import ru.murad.myvpn.model.VpnEntitlement;
import ru.murad.myvpn.model.VpnPolicyStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountVpnAccessServiceImplTest {
    @Test void provisionsFreeAccessWithoutSubscriptionAndPreservesStableExternalId() {
        Instant now = Instant.parse("2026-08-31T00:00:00Z"); UUID accountId = UUID.randomUUID(); UUID accessId = UUID.randomUUID();
        AccountVpnAccessTransactionService tx = mock(AccountVpnAccessTransactionService.class); VpnProvider provider = mock(VpnProvider.class);
        VpnAccess access = VpnAccess.builder().id(accessId).externalAccessId(accountId.toString()).providerClientKey("acc_" + accountId).providerName("FAKE")
                .status(VpnAccessStatus.PROVISIONING).issuedAt(now).createdAt(now).updatedAt(now)
                .desiredEntitlement(VpnEntitlement.FREE).policyStatus(VpnPolicyStatus.PENDING).policyGeneration(1).build();
        when(provider.providerName()).thenReturn("FAKE"); when(tx.reserveFree(eq(accountId), eq("FAKE"), any(), any())).thenReturn(access);
        when(provider.provision(any())).thenReturn(new ProvisionedVpnAccess("FAKE", accountId.toString(), "config"));
        when(tx.completeFree(eq(accessId), eq(1L), eq("FAKE"), eq("config"), any())).thenReturn(true);
        UUID result = new AccountVpnAccessServiceImpl(tx, provider, new VpnTrafficProperties(10, 30, 0), Clock.fixed(now, ZoneOffset.UTC)).ensureFreeVpnAccess(accountId);
        assertThat(result).isEqualTo(accessId); assertThat(access.getSubscription()).isNull();
        verify(provider).applyTrafficPolicy(accountId.toString(), "acc_" + accountId, VpnTrafficPolicy.limited(10));
    }
}
