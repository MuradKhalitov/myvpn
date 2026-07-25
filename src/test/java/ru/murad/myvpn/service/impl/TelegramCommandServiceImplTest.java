package ru.murad.myvpn.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.murad.myvpn.config.PaymentProperties;
import ru.murad.myvpn.dto.PaymentCheckoutResult;
import ru.murad.myvpn.dto.RegisterTelegramUserRequest;
import ru.murad.myvpn.dto.TelegramCallbackQuery;
import ru.murad.myvpn.dto.TelegramIncomingMessage;
import ru.murad.myvpn.dto.VpnTariffDto;
import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.model.PaymentStatus;
import ru.murad.myvpn.exception.OpenPaymentOrderDifferentTariffException;
import ru.murad.myvpn.exception.FakePaymentStateLostException;
import ru.murad.myvpn.repository.PaymentOrderRepository;
import ru.murad.myvpn.repository.TelegramUserRepository;
import ru.murad.myvpn.service.AdminAuthorizationService;
import ru.murad.myvpn.service.PaymentCheckoutService;
import ru.murad.myvpn.service.SubscriptionService;
import ru.murad.myvpn.service.TariffService;
import ru.murad.myvpn.service.UserService;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelegramCommandServiceImplTest {

    @Mock private UserService userService;
    @Mock private TariffService tariffService;
    @Mock private SubscriptionService subscriptionService;
    @Mock private AdminAuthorizationService adminAuthorizationService;
    @Mock private PaymentCheckoutService paymentCheckoutService;
    @Mock private TelegramUserRepository telegramUserRepository;
    @Mock private PaymentOrderRepository paymentOrderRepository;

    @Test
    void shouldRegisterUserOnStart() {
        TelegramIncomingMessage message =
                new TelegramIncomingMessage(1L, 2L, "user", "First", "Last", "/start");

        String response = service().handle(message);

        verify(userService).register(new RegisterTelegramUserRequest(
                1L, 2L, "user", "First", "Last"));
        assertThat(response).contains("Добро пожаловать в My VPN", "/tariffs");
    }

    @Test
    void buyMustExposeOnlyTariffCodeInCallback() {
        when(tariffService.findAvailableTariffs()).thenReturn(List.of(tariff()));

        var response = service().handleResponse(message("/buy"));

        assertThat(response.text()).contains("Выберите тариф");
        assertThat(response.keyboard().get(0).get(0).callbackData())
                .isEqualTo("buy:MONTH_1")
                .doesNotContain("90", "30");
        assertThat(response.keyboard().get(0).get(0).toString())
                .doesNotContain("buy:MONTH_1");
    }

    @Test
    void tariffCallbackMustReturnSafeUrlButton() {
        UUID orderId = UUID.randomUUID();
        var checkout = new PaymentCheckoutResult(
                orderId, "Month", new BigDecimal("90.00"), "RUB",
                30, PaymentStatus.PENDING,
                URI.create("https://example.invalid/fake-pay/abcdefghijklmnop"),
                Instant.parse("2026-07-25T11:00:00Z"));
        when(paymentCheckoutService.startCheckout(1L, "MONTH_1"))
                .thenReturn(checkout);

        var response = service().handleCallback(
                new TelegramCallbackQuery(1L, 1L, "buy:MONTH_1"));

        assertThat(response.text()).contains("Month", "90.00 RUB", "30");
        assertThat(response.keyboard().get(0).get(0).url())
                .isEqualTo(checkout.confirmationUrl().toString());
        assertThat(response.toString()).doesNotContain("fake-pay", "abcdefghijklmnop");
        assertThat(response.keyboard().get(1).get(0).callbackData())
                .isEqualTo("payment:check");
        for (String representation : List.of(
                checkout.toString(), String.valueOf(checkout),
                Objects.toString(checkout), String.format("%s", checkout))) {
            assertThat(representation)
                    .doesNotContain(orderId.toString(), "example.invalid");
        }
    }

    @Test
    void callbackTariffCodeMustUseStrictAsciiFormat() {
        for (String data : List.of(
                "buy:", "buy:month", "buy:MONTH:1", "buy:MONTH 1",
                "buy:MONTH\n1", "buy:АБВ", "buy:" + "A".repeat(33))) {
            var response = service().handleCallback(
                    new TelegramCallbackQuery(1L, 1L, data));
            assertThat(response.keyboard()).isEmpty();
            assertThat(response.text()).contains("Некорректный тариф");
        }
    }

    @Test
    void callbackDtoToStringMustRedactIdentityAndData() {
        TelegramCallbackQuery callback =
                new TelegramCallbackQuery(12345L, 67890L, "buy:MONTH_1");

        for (String representation : List.of(
                callback.toString(), String.valueOf(callback),
                Objects.toString(callback), String.format("%s", callback))) {
            assertThat(representation)
                    .doesNotContain("12345", "67890", "buy:MONTH_1");
        }
    }

    @Test
    void differentTariffCheckoutMustReturnExplicitSafeMessage() {
        when(paymentCheckoutService.startCheckout(1L, "MONTH_1"))
                .thenThrow(new OpenPaymentOrderDifferentTariffException());

        var response = service().handleCallback(
                new TelegramCallbackQuery(1L, 1L, "buy:MONTH_1"));

        assertThat(response.text())
                .contains("незавершённый платёж по другому тарифу");
        assertThat(response.keyboard()).isEmpty();
    }

    @Test
    void lostFakeStateMustReturnRecoveryInstruction() {
        when(paymentCheckoutService.checkCurrentPayment(1L))
                .thenThrow(new FakePaymentStateLostException());

        var response = service().handleCallback(
                new TelegramCallbackQuery(1L, 1L, "payment:check"));

        assertThat(response.text()).contains("/fakepay_reset");
        assertThat(response.keyboard()).isEmpty();
    }

    @Test
    void shouldValidateAdministrativeCommandArguments() {
        assertThat(service().handle(message("/activate 123")))
                .contains("Формат: /activate <telegramId> <tariffCode>");
    }

    private TelegramCommandServiceImpl service() {
        return new TelegramCommandServiceImpl(
                userService,
                tariffService,
                subscriptionService,
                adminAuthorizationService,
                paymentCheckoutService,
                new PaymentProperties(
                        PaymentProviderType.FAKE,
                        Duration.ofHours(1),
                        URI.create("https://example.invalid/payment-return"),
                        true),
                telegramUserRepository,
                paymentOrderRepository,
                Optional.empty(),
                Optional.empty());
    }

    private VpnTariffDto tariff() {
        return new VpnTariffDto(
                UUID.randomUUID(), "MONTH_1", "Month",
                "VPN", 30, new BigDecimal("90.00"), "RUB");
    }

    private TelegramIncomingMessage message(String text) {
        return new TelegramIncomingMessage(1L, 1L, null, "First", null, text);
    }
}
