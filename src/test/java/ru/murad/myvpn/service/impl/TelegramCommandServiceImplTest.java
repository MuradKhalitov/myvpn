package ru.murad.myvpn.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.murad.myvpn.dto.RegisterTelegramUserRequest;
import ru.murad.myvpn.dto.TelegramIncomingMessage;
import ru.murad.myvpn.dto.VpnTariffDto;
import ru.murad.myvpn.service.AdminAuthorizationService;
import ru.murad.myvpn.service.SubscriptionService;
import ru.murad.myvpn.service.TariffService;
import ru.murad.myvpn.service.UserService;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelegramCommandServiceImplTest {

    @Mock private UserService userService;
    @Mock private TariffService tariffService;
    @Mock private SubscriptionService subscriptionService;
    @Mock private AdminAuthorizationService adminAuthorizationService;

    @Test
    void shouldRegisterUserOnStart() {
        TelegramCommandServiceImpl service = service();
        TelegramIncomingMessage message =
                new TelegramIncomingMessage(1L, 2L, "user", "First", "Last", "/start");

        String response = service.handle(message);

        verify(userService).register(new RegisterTelegramUserRequest(
                1L, 2L, "user", "First", "Last"));
        assertThat(response).contains("Добро пожаловать в My VPN", "/tariffs");
    }

    @Test
    void shouldFormatAvailableTariffs() {
        when(tariffService.findAvailableTariffs()).thenReturn(List.of(
                new VpnTariffDto(UUID.randomUUID(), "MONTH_1", "1 месяц",
                        "VPN-доступ на 30 дней", 30, new BigDecimal("90.00"), "RUB")));

        String response = service().handle(message("/tariffs"));

        assertThat(response).contains("MONTH_1", "1 месяц", "90.00 RUB");
    }

    @Test
    void shouldValidateAdministrativeCommandArguments() {
        String response = service().handle(message("/activate 123"));

        assertThat(response).contains("Формат: /activate <telegramId> <tariffCode>");
    }

    private TelegramCommandServiceImpl service() {
        return new TelegramCommandServiceImpl(
                userService, tariffService, subscriptionService, adminAuthorizationService);
    }

    private TelegramIncomingMessage message(String text) {
        return new TelegramIncomingMessage(1L, 1L, null, "First", null, text);
    }
}
