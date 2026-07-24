package ru.murad.myvpn.service.impl;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.murad.myvpn.client.ProvisionedVpnAccess;
import ru.murad.myvpn.mapper.SubscriptionMapper;
import ru.murad.myvpn.model.Subscription;
import ru.murad.myvpn.model.SubscriptionStatus;
import ru.murad.myvpn.model.VpnAccess;
import ru.murad.myvpn.repository.SubscriptionRepository;
import ru.murad.myvpn.repository.TelegramUserRepository;
import ru.murad.myvpn.repository.VpnAccessRepository;
import ru.murad.myvpn.repository.VpnTariffRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubscriptionTransactionServiceImplTest {

    @Test
    void realThreeXUiConfigurationMustNotBePersisted() {
        SubscriptionRepository subscriptions =
                mock(SubscriptionRepository.class);
        VpnAccessRepository accesses = mock(VpnAccessRepository.class);
        SubscriptionMapper mapper = mock(SubscriptionMapper.class);
        SubscriptionTransactionServiceImpl service =
                new SubscriptionTransactionServiceImpl(
                        mock(TelegramUserRepository.class),
                        mock(VpnTariffRepository.class),
                        subscriptions,
                        accesses,
                        mapper);
        UUID subscriptionId = UUID.randomUUID();
        Subscription subscription = Subscription.builder()
                .id(subscriptionId)
                .status(SubscriptionStatus.PENDING)
                .createdAt(Instant.EPOCH)
                .updatedAt(Instant.EPOCH)
                .build();
        when(subscriptions.findById(subscriptionId))
                .thenReturn(Optional.of(subscription));
        String bearerSecret = "vless://must-not-be-persisted";

        service.completeProvision(
                subscriptionId,
                new ProvisionedVpnAccess(
                        "3X_UI", subscriptionId.toString(), bearerSecret),
                Instant.parse("2026-07-24T10:00:00Z"));

        ArgumentCaptor<VpnAccess> captor =
                ArgumentCaptor.forClass(VpnAccess.class);
        verify(accesses).save(captor.capture());
        assertThat(captor.getValue().getConfigurationData()).isNull();
        assertThat(captor.getValue().getExternalAccessId())
                .isEqualTo(subscriptionId.toString());
    }
}
