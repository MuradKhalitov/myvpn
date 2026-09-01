package ru.murad.myvpn.application.vpn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.murad.myvpn.config.VpnTrafficProperties;
import ru.murad.myvpn.model.*;
import ru.murad.myvpn.repository.SubscriptionRepository;
import ru.murad.myvpn.repository.VpnAccessRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CurrentVpnAccessQueryServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-02T00:00:00Z");
    @Mock SubscriptionRepository subscriptions;
    @Mock VpnAccessRepository accesses;

    @Test
    void missingAccessIsProvisioningWithoutProviderCall() {
        UUID accountId = UUID.randomUUID();
        when(accesses.findByAccountId(accountId)).thenReturn(Optional.empty());
        when(subscriptions.findFirstByAccountIdAndStatusAndExpiresAtAfterOrderByExpiresAtDesc(any(), any(), any()))
                .thenReturn(Optional.empty());

        VpnAccessResponse response = service().getCurrentAccess(accountId);

        assertThat(response.status()).isEqualTo(VpnAccessApiStatus.PROVISIONING);
        assertThat(response.entitlement()).isEqualTo(VpnEntitlement.FREE);
        assertThat(response.configuration()).isNull();
        assertThat(response.quota().limitBytes()).isEqualTo(5_368_709_120L);
        verifyNoMoreInteractions(accesses);
    }

    @Test
    void readyFreeAccessReturnsConfigurationAndNeverProviderInternals() {
        UUID accountId = UUID.randomUUID();
        Account account = Account.builder().id(accountId).build();
        VpnAccess access = VpnAccess.builder().id(UUID.randomUUID()).account(account)
                .providerName("FAKE").externalAccessId("internal-id").providerClientKey("secret-key")
                .configurationData("vless://sensitive").status(VpnAccessStatus.ACTIVE)
                .createdAt(NOW).updatedAt(NOW).issuedAt(NOW).build();
        when(accesses.findByAccountId(accountId)).thenReturn(Optional.of(access));
        when(subscriptions.findFirstByAccountIdAndStatusAndExpiresAtAfterOrderByExpiresAtDesc(any(), any(), any()))
                .thenReturn(Optional.empty());

        VpnAccessResponse response = service().getCurrentAccess(accountId);

        assertThat(response.status()).isEqualTo(VpnAccessApiStatus.READY);
        assertThat(response.entitlement()).isEqualTo(VpnEntitlement.FREE);
        assertThat(response.configuration()).isEqualTo("vless://sensitive");
        assertThat(response.premiumExpiresAt()).isNull();
    }

    @Test
    void activePremiumReturnsUnlimitedQuotaAndExpiry() {
        UUID accountId = UUID.randomUUID();
        Account account = Account.builder().id(accountId).build();
        Subscription subscription = Subscription.builder().id(UUID.randomUUID()).account(account)
                .status(SubscriptionStatus.ACTIVE).expiresAt(NOW.plusSeconds(3600)).build();
        VpnAccess access = VpnAccess.builder().id(UUID.randomUUID()).account(account)
                .providerName("FAKE").externalAccessId("internal-id").configurationData("vless://sensitive")
                .status(VpnAccessStatus.ACTIVE).createdAt(NOW).updatedAt(NOW).issuedAt(NOW).build();
        when(accesses.findByAccountId(accountId)).thenReturn(Optional.of(access));
        when(subscriptions.findFirstByAccountIdAndStatusAndExpiresAtAfterOrderByExpiresAtDesc(accountId, SubscriptionStatus.ACTIVE, NOW))
                .thenReturn(Optional.of(subscription));

        VpnAccessResponse response = service().getCurrentAccess(accountId);

        assertThat(response.entitlement()).isEqualTo(VpnEntitlement.PREMIUM);
        assertThat(response.status()).isEqualTo(VpnAccessApiStatus.READY);
        assertThat(response.quota()).isNull();
        assertThat(response.premiumExpiresAt()).isEqualTo(subscription.getExpiresAt());
    }

    private CurrentVpnAccessQueryService service() {
        return new CurrentVpnAccessQueryService(subscriptions, accesses,
                new VpnTrafficProperties(5_368_709_120L, 30, 0), Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
