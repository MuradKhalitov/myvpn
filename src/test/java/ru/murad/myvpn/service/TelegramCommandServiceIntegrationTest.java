package ru.murad.myvpn.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.murad.myvpn.dto.TelegramIncomingMessage;
import ru.murad.myvpn.dto.TelegramCallbackQuery;
import ru.murad.myvpn.client.FakePaymentProvider;
import ru.murad.myvpn.model.PaymentActivationStatus;
import ru.murad.myvpn.model.PaymentStatus;
import ru.murad.myvpn.model.PaymentOrder;
import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.repository.PaymentOrderRepository;
import ru.murad.myvpn.model.SubscriptionStatus;
import ru.murad.myvpn.model.VpnAccessStatus;
import ru.murad.myvpn.repository.SubscriptionRepository;
import ru.murad.myvpn.repository.TelegramUserRepository;
import ru.murad.myvpn.repository.VpnAccessRepository;
import ru.murad.myvpn.repository.VpnTariffRepository;
import ru.murad.myvpn.service.impl.FakePaymentRecoveryServiceImpl;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
class TelegramCommandServiceIntegrationTest {

    private static final long ADMIN_ID = 100L;
    private static final long USER_ID = 200L;

    @Container
    static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void configureApplication(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
        registry.add("telegram.admin-ids", () -> String.valueOf(ADMIN_ID));
    }

    @Autowired
    private TelegramCommandService commandService;

    @Autowired
    private TelegramUserRepository userRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private VpnAccessRepository vpnAccessRepository;

    @Autowired
    private PaymentOrderRepository paymentOrderRepository;

    @Autowired
    private Clock clock;

    @Autowired
    private VpnTariffRepository tariffRepository;

    @Test
    void shouldCompleteFakeVpnSubscriptionLifecycleThroughCommands() {
        String welcome = commandService.handle(userMessage("/start"));
        String tariffs = commandService.handle(userMessage("/tariffs"));
        String activation = commandService.handle(adminMessage("/activate " + USER_ID + " MONTH_1"));
        String subscription = commandService.handle(userMessage("/subscription"));
        String userDetails = commandService.handle(adminMessage("/user " + USER_ID));
        String revocation = commandService.handle(adminMessage("/revoke " + USER_ID));

        assertThat(welcome).contains("My VPN", "/tariffs", "/subscription");
        assertThat(tariffs).contains("MONTH_1", "90.00 RUB", "YEAR_1", "720.00 RUB");
        assertThat(activation).isNotBlank();
        assertThat(subscription).contains("FAKE", "fake-vpn://");
        assertThat(userDetails).contains(String.valueOf(USER_ID), "@integration_user", "FAKE");
        assertThat(revocation).isNotBlank();

        var user = userRepository.findByTelegramId(USER_ID).orElseThrow();
        var persistedSubscription = subscriptionRepository
                .findFirstByUserTelegramIdAndStatusOrderByExpiresAtDesc(
                        USER_ID, SubscriptionStatus.REVOKED)
                .orElseThrow();
        var access = vpnAccessRepository.findBySubscriptionId(persistedSubscription.getId())
                .orElseThrow();

        assertThat(user.getChatId()).isEqualTo(USER_ID);
        assertThat(persistedSubscription.getStatus()).isEqualTo(SubscriptionStatus.REVOKED);
        assertThat(access.getStatus()).isEqualTo(VpnAccessStatus.REVOKED);
        assertThat(access.getConfigurationData()).startsWith("fake-vpn://");
    }

    @Test
    void shouldRejectAdministrativeCommandFromRegularUser() {
        commandService.handle(userMessage("/start"));

        String response = commandService.handle(userMessage(
                "/activate " + USER_ID + " MONTH_1"));

        assertThat(response).isNotBlank();
        assertThat(subscriptionRepository.count()).isZero();
        assertThat(vpnAccessRepository.count()).isZero();
    }

    @Test
    void shouldCreateAndCheckFakeCheckoutWithoutActivatingSubscription() {
        commandService.handle(userMessage("/start"));

        var tariffs = commandService.handleResponse(userMessage("/buy"));
        var checkout = commandService.handleCallback(
                new TelegramCallbackQuery(USER_ID, USER_ID, "buy:MONTH_1"));
        var repeated = commandService.handleCallback(
                new TelegramCallbackQuery(USER_ID, USER_ID, "buy:MONTH_1"));
        var pending = commandService.handleCallback(
                new TelegramCallbackQuery(USER_ID, USER_ID, "payment:check"));

        assertThat(tariffs.keyboard().get(0).get(0).callbackData())
                .isEqualTo("buy:MONTH_1");
        assertThat(checkout.keyboard().get(0).get(0).url())
                .startsWith("https://example.invalid/fake-pay/");
        assertThat(repeated.keyboard().get(0).get(0).url())
                .isEqualTo(checkout.keyboard().get(0).get(0).url());
        assertThat(pending.text()).contains("Ожидает оплаты");
        assertThat(paymentOrderRepository.count()).isEqualTo(1);

        var user = userRepository.findByTelegramId(USER_ID).orElseThrow();
        var order = paymentOrderRepository.findOpenByUser(user.getId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(order.getActivationStatus())
                .isEqualTo(PaymentActivationStatus.NOT_READY);
        assertThat(subscriptionRepository.count()).isZero();

        String denied = commandService.handle(userMessage(
                "/fakepay_success " + USER_ID));
        String succeeded = commandService.handle(adminMessage(
                "/fakepay_success " + USER_ID));
        var checked = commandService.handleCallback(
                new TelegramCallbackQuery(USER_ID, USER_ID, "payment:check"));

        assertThat(denied).contains("Доступ запрещён");
        assertThat(succeeded).contains("SUCCEEDED");
        assertThat(checked.text()).contains("Оплата подтверждена");
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(order.getActivationStatus())
                .isEqualTo(PaymentActivationStatus.NOT_READY);
        assertThat(subscriptionRepository.count()).isZero();
    }

    @Test
    void resetMustRefuseExistingPaymentAndRecoverAfterProviderRestart() {
        commandService.handle(userMessage("/start"));
        commandService.handleCallback(
                new TelegramCallbackQuery(USER_ID, USER_ID, "buy:MONTH_1"));
        var user = userRepository.findByTelegramId(USER_ID).orElseThrow();
        var oldOrder = paymentOrderRepository.findOpenByUser(user.getId()).orElseThrow();

        String denied = commandService.handle(userMessage(
                "/fakepay_reset " + USER_ID));
        String liveReset = commandService.handle(adminMessage(
                "/fakepay_reset " + USER_ID));
        assertThat(liveReset).contains("существует");
        assertThat(oldOrder.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(paymentOrderRepository.count()).isEqualTo(1);

        FakePaymentRecoveryService restartedRecovery =
                new FakePaymentRecoveryServiceImpl(
                        administratorId -> {
                            if (administratorId != ADMIN_ID) {
                                throw new IllegalArgumentException("Access denied");
                            }
                        },
                        userRepository,
                        paymentOrderRepository,
                        clock,
                        new FakePaymentProvider(clock));
        restartedRecovery.resetLostPayment(ADMIN_ID, USER_ID);
        var replacement = commandService.handleCallback(
                new TelegramCallbackQuery(USER_ID, USER_ID, "buy:MONTH_1"));

        assertThat(denied).contains("Доступ запрещён");
        assertThat(oldOrder.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(oldOrder.getSafeFailureCode())
                .isEqualTo("FAKE_PROVIDER_STATE_LOST");
        assertThat(replacement.keyboard()).isNotEmpty();
        assertThat(paymentOrderRepository.count()).isEqualTo(2);
        assertThat(subscriptionRepository.count()).isZero();
    }

    @Test
    void fakeAdministrativeCommandsMustRejectNonFakeOrder() {
        commandService.handle(userMessage("/start"));
        var user = userRepository.findByTelegramId(USER_ID).orElseThrow();
        var tariff = tariffRepository.findByCodeAndActiveTrue("MONTH_1").orElseThrow();
        PaymentOrder order = PaymentOrder.create(
                user, tariff, PaymentProviderType.YOOKASSA,
                Instant.now(clock), Duration.ofHours(1));
        order.markCreating(Instant.now(clock));
        order.markPending(
                "external-payment",
                "https://example.invalid/fake-pay/abcdefghijklmnop",
                Instant.now(clock), null, Instant.now(clock));
        paymentOrderRepository.saveAndFlush(order);

        String success = commandService.handle(adminMessage(
                "/fakepay_success " + USER_ID));
        String reset = commandService.handle(adminMessage(
                "/fakepay_reset " + USER_ID));

        assertThat(success).contains("fake");
        assertThat(success).doesNotContain("SUCCEEDED");
        assertThat(reset).doesNotContain("закрыт");
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    private TelegramIncomingMessage userMessage(String text) {
        return new TelegramIncomingMessage(
                USER_ID, USER_ID, "integration_user", "Integration", "User", text);
    }

    private TelegramIncomingMessage adminMessage(String text) {
        return new TelegramIncomingMessage(
                ADMIN_ID, ADMIN_ID, "integration_admin", "Admin", null, text);
    }
}
