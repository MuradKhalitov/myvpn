package ru.murad.myvpn.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.murad.myvpn.adapter.telegram.TelegramUserIdResolver;
import ru.murad.myvpn.application.vpn.CurrentVpnAccessQuery;
import ru.murad.myvpn.application.vpn.VpnAccessView;
import ru.murad.myvpn.dto.TelegramIncomingMessage;
import ru.murad.myvpn.model.VpnAccessStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VpnConfigurationCommandServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-08-30T10:00:00Z");
    private static final long TELEGRAM_ID = 123L;

    @Mock private TelegramUserIdResolver userIdResolver;
    @Mock private CurrentVpnAccessQuery vpnAccessQuery;

    @Test
    void rejectsGroupChatBeforeResolvingIdentity() {
        String response = service().configurationForOwner(
                new TelegramIncomingMessage(
                        TELEGRAM_ID, 999L, null, null, null, "/vpn"));

        assertThat(response).contains("личном чате");
        verify(userIdResolver, never()).resolve(TELEGRAM_ID);
    }

    @Test
    void resolvesTelegramIdentityAndFormatsApplicationView() {
        UUID userId = UUID.randomUUID();
        String configuration = "vless://secret@example.invalid:443";
        when(userIdResolver.resolve(TELEGRAM_ID)).thenReturn(userId);
        when(vpnAccessQuery.findCurrent(userId)).thenReturn(Optional.of(
                new VpnAccessView(userId, VpnAccessStatus.ACTIVE, "3X_UI",
                        configuration, NOW.plusSeconds(3600))));

        String response = service().configurationForOwner(privateMessage());

        assertThat(response).contains(configuration, "Действует до");
        verify(vpnAccessQuery).findCurrent(userId);
    }

    @Test
    void doesNotReturnRevokedConfiguration() {
        UUID userId = UUID.randomUUID();
        String configuration = "vless://revoked-secret@example.invalid:443";
        when(userIdResolver.resolve(TELEGRAM_ID)).thenReturn(userId);
        when(vpnAccessQuery.findCurrent(userId)).thenReturn(Optional.of(
                new VpnAccessView(userId, VpnAccessStatus.REVOKED, "3X_UI",
                        configuration, NOW.plusSeconds(3600))));

        assertThat(service().configurationForOwner(privateMessage()))
                .doesNotContain(configuration)
                .contains("Обратитесь в поддержку");
    }

    private VpnConfigurationCommandServiceImpl service() {
        return new VpnConfigurationCommandServiceImpl(
                userIdResolver, vpnAccessQuery,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private TelegramIncomingMessage privateMessage() {
        return new TelegramIncomingMessage(
                TELEGRAM_ID, TELEGRAM_ID, null, null, null, "/vpn");
    }
}
