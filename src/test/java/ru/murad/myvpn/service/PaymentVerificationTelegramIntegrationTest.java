package ru.murad.myvpn.service;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.PostgreSQLContainer;
import ru.murad.myvpn.client.FakePaymentProvider;
import ru.murad.myvpn.dto.TelegramCallbackQuery;
import ru.murad.myvpn.dto.TelegramIncomingMessage;
import ru.murad.myvpn.model.PaymentActivationStatus;
import ru.murad.myvpn.model.PaymentOrder;
import ru.murad.myvpn.model.PaymentStatus;
import ru.murad.myvpn.exception.PaymentProviderUncertainException;
import ru.murad.myvpn.repository.PaymentOrderRepository;
import ru.murad.myvpn.repository.TelegramUserRepository;
import ru.murad.myvpn.repository.SubscriptionRepository;
import ru.murad.myvpn.repository.VpnAccessRepository;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doThrow;

@SpringBootTest(properties = "payment.verification.min-interval=5s")
@ActiveProfiles("test")
@Testcontainers
class PaymentVerificationTelegramIntegrationTest {

    private static final long ADMIN_ID = 9900L;
    private static final long BASE_USER_ID = 30000L;

    @Container
    static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer<>("postgres:16.3-alpine");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
        registry.add("telegram.admin-ids", () -> String.valueOf(ADMIN_ID));
    }

    @Autowired TelegramCommandService commandService;
    @Autowired PaymentOrderRepository orderRepository;
    @Autowired TelegramUserRepository userRepository;
    @Autowired SubscriptionRepository subscriptionRepository;
    @Autowired VpnAccessRepository vpnAccessRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @SpyBean FakePaymentProvider provider;

    @BeforeEach
    void clean() {
        orderRepository.deleteAll();
        userRepository.deleteAll();
        clearInvocations(provider);
    }

    @Test
    void successCheckIsTrustedAndRepeatedCheckIsNoOp() {
        long userId = BASE_USER_ID + 1;
        PaymentOrder order = checkout(userId);
        commandService.handle(adminMessage("/fakepay_success " + userId));
        entityManager.clear();
        assertThat(read(order.getId()).getStatus()).isEqualTo(PaymentStatus.PENDING);
        clearInvocations(provider);

        String checked = check(userId);
        PaymentOrder succeeded = read(order.getId());
        assertThat(checked).contains("Оплата подтверждена");
        assertThat(succeeded.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(succeeded.getActivationStatus()).isEqualTo(PaymentActivationStatus.PENDING);
        assertThat(succeeded.getPaidAt()).isNotNull();
        assertThat(checked).doesNotContain(order.getId().toString(), order.getProviderPaymentId());
        assertThat(subscriptionRepository.count()).isZero();
        assertThat(vpnAccessRepository.count()).isZero();

        Instant paidAt = succeeded.getPaidAt();
        String repeated = check(userId);
        PaymentOrder afterRepeat = read(order.getId());
        assertThat(repeated).contains("Оплата подтверждена");
        assertThat(afterRepeat.getPaidAt()).isEqualTo(paidAt);
        verify(provider, times(1)).getPayment(order.getProviderPaymentId());
    }

    @Test
    void pendingCheckDoesNotMutateOrder() {
        long userId = BASE_USER_ID + 2;
        PaymentOrder order = checkout(userId);
        String response = check(userId);
        PaymentOrder reread = read(order.getId());
        assertThat(response).isNotBlank().doesNotContain(order.getId().toString());
        assertThat(reread.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(reread.getActivationStatus()).isEqualTo(PaymentActivationStatus.NOT_READY);
        assertThat(reread.getPaidAt()).isNull();
    }

    @Test
    void canceledCheckIsTerminalAndNewCheckoutIsAllowed() {
        long userId = BASE_USER_ID + 3;
        PaymentOrder order = checkout(userId);
        provider.markCanceled(order.getProviderPaymentId());
        assertThat(check(userId)).isNotBlank();
        assertThat(read(order.getId()).getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(commandService.handleCallback(
                new TelegramCallbackQuery(userId, userId, "buy:MONTH_1"))).isNotNull();
        assertThat(orderRepository.findAllByUserOrderByCreatedAtDesc(
                userRepository.findByTelegramId(userId).orElseThrow().getId())).hasSize(2);
    }

    @Test
    void checkoutIncompleteDoesNotCallProvider() {
        long userId = BASE_USER_ID + 4;
        PaymentOrder order = checkout(userId);
        jdbc.update("update payment_orders set status='CREATING', provider_payment_id=null where id=?",
                order.getId());
        clearInvocations(provider);
        String response = check(userId);
        assertThat(response).isNotBlank();
        verify(provider, times(0)).getPayment(org.mockito.ArgumentMatchers.anyString());
        assertThat(read(order.getId()).getStatus()).isEqualTo(PaymentStatus.CREATING);
    }

    @Test
    void noOrderAndManualReviewHaveSafeResponses() {
        assertThat(check(BASE_USER_ID + 5)).doesNotContain("Exception", "paymentOrderId");
        long userId = BASE_USER_ID + 6;
        PaymentOrder order = checkout(userId);
        provider.markCanceled(order.getProviderPaymentId());
        // The provider state is intentionally unavailable after local setup.
        jdbc.update("update payment_orders set status='MANUAL_REVIEW_REQUIRED', "
                        + "safe_failure_code='PAYMENT_AMOUNT_MISMATCH' where id=?", order.getId());
        entityManager.clear();
        String response = check(userId);
        assertThat(response).isNotBlank();
        assertThat(response).doesNotContain("PAYMENT_AMOUNT_MISMATCH", order.getId().toString());
    }

    @Test
    void repeatedPendingCheckIsTooEarlyWithoutSecondProviderGet() {
        PaymentOrder order = checkout(BASE_USER_ID + 7);
        clearInvocations(provider);
        check(BASE_USER_ID + 7);
        String response = check(BASE_USER_ID + 7);
        assertThat(response).isNotBlank();
        verify(provider, times(1)).getPayment(order.getProviderPaymentId());
        assertThat(read(order.getId()).getVerificationAttempts()).isEqualTo(1);
    }

    @Test
    void providerUnavailableIsSafeAndLeavesOrderPending() {
        long userId = BASE_USER_ID + 8;
        PaymentOrder order = checkout(userId);
        doThrow(new PaymentProviderUncertainException("secret " + order.getProviderPaymentId()))
                .when(provider).getPayment(order.getProviderPaymentId());
        String response = check(userId);
        assertThat(response).isNotBlank().doesNotContain(order.getProviderPaymentId(), order.getId().toString());
        assertThat(read(order.getId()).getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void failedOrderProducesSafeTerminalResponseWithoutProviderGet() {
        long userId = BASE_USER_ID + 9;
        PaymentOrder order = checkout(userId);
        jdbc.update("update payment_orders set status='FAILED', safe_failure_code='TEST_FAILURE' where id=?",
                order.getId());
        clearInvocations(provider);
        String response = check(userId);
        assertThat(response).isNotBlank().doesNotContain("TEST_FAILURE", order.getId().toString());
        verify(provider, times(0)).getPayment(org.mockito.ArgumentMatchers.anyString());
    }

    private PaymentOrder checkout(long userId) {
        commandService.handle(userMessage(userId, "/start"));
        commandService.handleCallback(new TelegramCallbackQuery(userId, userId, "buy:MONTH_1"));
        return orderRepository.findOpenByUser(
                userRepository.findByTelegramId(userId).orElseThrow().getId()).orElseThrow();
    }

    private String check(long userId) {
        return commandService.handleCallback(
                new TelegramCallbackQuery(userId, userId, "payment:check")).text();
    }

    private PaymentOrder read(UUID id) {
        entityManager.clear();
        return orderRepository.findById(id).orElseThrow();
    }

    private TelegramIncomingMessage userMessage(long userId, String text) {
        return new TelegramIncomingMessage(userId, userId, "integration_user",
                "Integration", "User", text);
    }

    private TelegramIncomingMessage adminMessage(String text) {
        return userMessage(ADMIN_ID, text);
    }
}
