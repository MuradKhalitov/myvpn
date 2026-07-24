package ru.murad.myvpn.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.murad.myvpn.client.ProvisionedVpnAccess;
import ru.murad.myvpn.client.VpnExtensionRequest;
import ru.murad.myvpn.client.VpnProvider;
import ru.murad.myvpn.dto.ActivateSubscriptionRequest;
import ru.murad.myvpn.dto.SubscriptionDto;
import ru.murad.myvpn.exception.AdministratorAccessDeniedException;
import ru.murad.myvpn.mapper.SubscriptionMapper;
import ru.murad.myvpn.model.Subscription;
import ru.murad.myvpn.model.SubscriptionStatus;
import ru.murad.myvpn.model.TelegramUser;
import ru.murad.myvpn.model.UserRole;
import ru.murad.myvpn.model.VpnAccess;
import ru.murad.myvpn.model.VpnAccessStatus;
import ru.murad.myvpn.model.VpnTariff;
import ru.murad.myvpn.repository.SubscriptionRepository;
import ru.murad.myvpn.repository.TelegramUserRepository;
import ru.murad.myvpn.repository.VpnAccessRepository;
import ru.murad.myvpn.repository.VpnTariffRepository;
import ru.murad.myvpn.service.AdminAuthorizationService;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");
    private static final long ADMIN_ID = 101L;
    private static final long USER_TELEGRAM_ID = 202L;

    @Mock private AdminAuthorizationService adminAuthorizationService;
    @Mock private TelegramUserRepository userRepository;
    @Mock private VpnTariffRepository tariffRepository;
    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private VpnAccessRepository accessRepository;
    @Mock private VpnProvider vpnProvider;
    @Mock private SubscriptionMapper subscriptionMapper;

    private SubscriptionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SubscriptionServiceImpl(
                adminAuthorizationService,
                userRepository,
                tariffRepository,
                subscriptionRepository,
                accessRepository,
                vpnProvider,
                subscriptionMapper,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void shouldProvisionNewSubscriptionStartingNow() {
        TelegramUser user = user();
        VpnTariff tariff = tariff("MONTH_1", 30);
        ActivateSubscriptionRequest request =
                new ActivateSubscriptionRequest(ADMIN_ID, USER_TELEGRAM_ID, "MONTH_1");
        SubscriptionDto expected = org.mockito.Mockito.mock(SubscriptionDto.class);
        when(userRepository.findByTelegramId(USER_TELEGRAM_ID)).thenReturn(Optional.of(user));
        when(tariffRepository.findByCodeAndActiveTrue("MONTH_1")).thenReturn(Optional.of(tariff));
        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByExpiresAtDesc(
                user.getId(), SubscriptionStatus.ACTIVE)).thenReturn(Optional.empty());
        when(vpnProvider.provision(any())).thenReturn(
                new ProvisionedVpnAccess("FAKE", "external-1", "fake-config"));
        when(subscriptionMapper.toDto(any(), any())).thenReturn(expected);

        SubscriptionDto result = service.activate(request);

        ArgumentCaptor<Subscription> subscriptionCaptor =
                ArgumentCaptor.forClass(Subscription.class);
        verify(subscriptionRepository).save(subscriptionCaptor.capture());
        Subscription saved = subscriptionCaptor.getValue();
        assertThat(saved.getStartsAt()).isEqualTo(NOW);
        assertThat(saved.getExpiresAt()).isEqualTo(NOW.plusSeconds(30L * 24 * 60 * 60));
        assertThat(saved.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);

        ArgumentCaptor<VpnAccess> accessCaptor = ArgumentCaptor.forClass(VpnAccess.class);
        verify(accessRepository).save(accessCaptor.capture());
        assertThat(accessCaptor.getValue().getProviderName()).isEqualTo("FAKE");
        assertThat(accessCaptor.getValue().getConfigurationData()).isEqualTo("fake-config");
        assertThat(result).isSameAs(expected);
    }

    @Test
    void shouldExtendActiveSubscriptionFromCurrentExpiration() {
        TelegramUser user = user();
        VpnTariff oldTariff = tariff("MONTH_1", 30);
        VpnTariff extensionTariff = tariff("MONTH_3", 90);
        Instant currentExpiration = NOW.plusSeconds(10 * 24 * 60 * 60L);
        Subscription subscription = subscription(user, oldTariff, currentExpiration);
        VpnAccess access = access(subscription);
        when(userRepository.findByTelegramId(USER_TELEGRAM_ID)).thenReturn(Optional.of(user));
        when(tariffRepository.findByCodeAndActiveTrue("MONTH_3"))
                .thenReturn(Optional.of(extensionTariff));
        when(subscriptionRepository.findFirstByUserIdAndStatusOrderByExpiresAtDesc(
                user.getId(), SubscriptionStatus.ACTIVE)).thenReturn(Optional.of(subscription));
        when(accessRepository.findBySubscriptionId(subscription.getId()))
                .thenReturn(Optional.of(access));

        service.activate(new ActivateSubscriptionRequest(
                ADMIN_ID, USER_TELEGRAM_ID, "MONTH_3"));

        assertThat(subscription.getExpiresAt())
                .isEqualTo(currentExpiration.plusSeconds(90L * 24 * 60 * 60));
        assertThat(subscription.getTariff()).isSameAs(extensionTariff);
        verify(vpnProvider).extend(new VpnExtensionRequest(
                access.getExternalAccessId(), subscription.getExpiresAt()));
        verify(vpnProvider, never()).provision(any());
    }

    @Test
    void shouldRejectUnauthorizedAdministratorBeforeReadingUserData() {
        ActivateSubscriptionRequest request =
                new ActivateSubscriptionRequest(ADMIN_ID, USER_TELEGRAM_ID, "MONTH_1");
        doThrow(new AdministratorAccessDeniedException())
                .when(adminAuthorizationService).checkAccess(ADMIN_ID);

        assertThatThrownBy(() -> service.activate(request))
                .isInstanceOf(AdministratorAccessDeniedException.class);

        verify(adminAuthorizationService).checkAccess(ADMIN_ID);
        verify(userRepository, never()).findByTelegramId(USER_TELEGRAM_ID);
    }

    private TelegramUser user() {
        return TelegramUser.builder()
                .id(UUID.randomUUID())
                .telegramId(USER_TELEGRAM_ID)
                .chatId(USER_TELEGRAM_ID)
                .role(UserRole.USER)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    private VpnTariff tariff(String code, int durationDays) {
        return VpnTariff.builder()
                .id(UUID.randomUUID())
                .code(code)
                .name(code)
                .durationDays(durationDays)
                .price(BigDecimal.ONE)
                .currency("RUB")
                .active(true)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    private Subscription subscription(
            TelegramUser user,
            VpnTariff tariff,
            Instant expiresAt
    ) {
        return Subscription.builder()
                .id(UUID.randomUUID())
                .user(user)
                .tariff(tariff)
                .status(SubscriptionStatus.ACTIVE)
                .startsAt(NOW.minusSeconds(3600))
                .expiresAt(expiresAt)
                .activatedByTelegramId(ADMIN_ID)
                .activatedAt(NOW.minusSeconds(3600))
                .createdAt(NOW.minusSeconds(3600))
                .updatedAt(NOW.minusSeconds(3600))
                .build();
    }

    private VpnAccess access(Subscription subscription) {
        return VpnAccess.builder()
                .id(UUID.randomUUID())
                .subscription(subscription)
                .providerName("FAKE")
                .externalAccessId("external-1")
                .configurationData("fake-config")
                .status(VpnAccessStatus.ACTIVE)
                .issuedAt(NOW)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }
}
