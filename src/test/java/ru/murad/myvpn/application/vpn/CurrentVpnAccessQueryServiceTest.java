package ru.murad.myvpn.application.vpn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.murad.myvpn.mapper.SubscriptionMapper;
import ru.murad.myvpn.model.Subscription;
import ru.murad.myvpn.model.SubscriptionStatus;
import ru.murad.myvpn.model.VpnAccess;
import ru.murad.myvpn.model.VpnAccessStatus;
import ru.murad.myvpn.repository.SubscriptionRepository;
import ru.murad.myvpn.repository.VpnAccessRepository;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CurrentVpnAccessQueryServiceTest {

    private static final Instant EXPIRES_AT =
            Instant.parse("2026-09-30T10:00:00Z");
    private static final String SECRET_CONFIGURATION =
            "vless://secret-user@example.invalid:443";

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private VpnAccessRepository vpnAccessRepository;
    @Mock private SubscriptionMapper subscriptionMapper;

    @Test
    void returnsEmptyWhenCurrentSubscriptionDoesNotExist() {
        UUID userId = UUID.randomUUID();

        assertThat(service().findCurrent(userId)).isEmpty();

        verify(subscriptionRepository)
                .findFirstByUserIdAndStatusOrderByExpiresAtDesc(
                        userId, SubscriptionStatus.ACTIVE);
    }

    @Test
    void returnsEmptyWhenVpnAccessDoesNotExist() {
        UUID userId = UUID.randomUUID();
        Subscription subscription = subscription();
        when(subscriptionRepository
                .findFirstByUserIdAndStatusOrderByExpiresAtDesc(
                        userId, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(subscription));

        assertThat(service().findCurrent(userId)).isEmpty();

        verify(vpnAccessRepository).findBySubscriptionId(subscription.getId());
    }

    @Test
    void returnsActiveVpnAccessWithoutExposingConfigurationInRepresentation() {
        assertAccessReturned(VpnAccessStatus.ACTIVE);
    }

    @Test
    void returnsRevokedVpnAccessWithExplicitStatus() {
        assertAccessReturned(VpnAccessStatus.REVOKED);
    }

    private void assertAccessReturned(VpnAccessStatus status) {
        UUID userId = UUID.randomUUID();
        Subscription subscription = subscription();
        VpnAccess access = VpnAccess.builder()
                .id(UUID.randomUUID())
                .subscription(subscription)
                .status(status)
                .providerName("3X_UI")
                .configurationData(SECRET_CONFIGURATION)
                .build();
        VpnAccessView expected = new VpnAccessView(
                subscription.getId(), status, "3X_UI",
                SECRET_CONFIGURATION, EXPIRES_AT);
        when(subscriptionRepository
                .findFirstByUserIdAndStatusOrderByExpiresAtDesc(
                        userId, SubscriptionStatus.ACTIVE))
                .thenReturn(Optional.of(subscription));
        when(vpnAccessRepository.findBySubscriptionId(subscription.getId()))
                .thenReturn(Optional.of(access));
        when(subscriptionMapper.toVpnAccessView(access)).thenReturn(expected);

        VpnAccessView result = service().findCurrent(userId).orElseThrow();

        assertThat(result).isEqualTo(expected);
        assertThat(result.configuration()).isEqualTo(SECRET_CONFIGURATION);
        for (String representation : List.of(
                result.toString(), String.valueOf(result),
                Objects.toString(result), String.format("%s", result))) {
            assertThat(representation)
                    .doesNotContain(SECRET_CONFIGURATION, "secret-user", "vless://")
                    .contains("configuration=redacted");
        }
    }

    private CurrentVpnAccessQueryService service() {
        return new CurrentVpnAccessQueryService(
                subscriptionRepository, vpnAccessRepository, subscriptionMapper);
    }

    private Subscription subscription() {
        return Subscription.builder()
                .id(UUID.randomUUID())
                .status(SubscriptionStatus.ACTIVE)
                .expiresAt(EXPIRES_AT)
                .build();
    }
}
