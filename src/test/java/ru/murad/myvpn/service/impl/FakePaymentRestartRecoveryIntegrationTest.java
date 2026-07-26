package ru.murad.myvpn.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.PostgreSQLContainer;
import ru.murad.myvpn.client.FakePaymentProvider;
import ru.murad.myvpn.dto.CreatePaymentCommand;
import ru.murad.myvpn.dto.PreparedPaymentVerification;
import ru.murad.myvpn.exception.FakePaymentStillExistsException;
import ru.murad.myvpn.exception.PaymentNotFoundException;
import ru.murad.myvpn.exception.PaymentProviderPermanentException;
import ru.murad.myvpn.exception.PaymentProviderUncertainException;
import ru.murad.myvpn.model.PaymentActivationStatus;
import ru.murad.myvpn.model.PaymentOrder;
import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.model.PaymentStatus;
import ru.murad.myvpn.model.TelegramUser;
import ru.murad.myvpn.model.UserRole;
import ru.murad.myvpn.model.VpnTariff;
import ru.murad.myvpn.repository.PaymentOrderRepository;
import ru.murad.myvpn.repository.TelegramUserRepository;
import ru.murad.myvpn.repository.VpnTariffRepository;
import ru.murad.myvpn.service.FakePaymentRecoveryService;
import ru.murad.myvpn.service.PaymentCheckoutService;
import ru.murad.myvpn.service.PaymentCheckoutTransactionService;
import ru.murad.myvpn.service.PaymentProviderRegistry;
import ru.murad.myvpn.service.PaymentVerificationTransactionService;
import ru.murad.myvpn.config.PaymentProperties;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "vpn.lifecycle.expiration-check-delay=3600000",
        "vpn.lifecycle.pending-recovery-delay=3600000"
})
@ActiveProfiles("test")
@Testcontainers
@Import(FakePaymentRestartRecoveryIntegrationTest.FixedClockConfiguration.class)
class FakePaymentRestartRecoveryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-25T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Container
    static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer<>("postgres:16.3-alpine");

    @DynamicPropertySource
    static void configurePostgresql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
    }

    @Autowired TelegramUserRepository userRepository;
    @Autowired VpnTariffRepository tariffRepository;
    @Autowired PaymentOrderRepository orderRepository;
    @Autowired PaymentVerificationTransactionService verificationTransactions;
    @Autowired PaymentCheckoutTransactionService checkoutTransactions;
    @Autowired PaymentProperties paymentProperties;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return CLOCK;
        }
    }

    @BeforeEach
    void clean() {
        orderRepository.deleteAll();
        userRepository.deleteAll();
        tariffRepository.deleteAll();
    }

    @Test
    void restartMakesMissingPaymentManualReviewThenResetMarksStateLost() {
        Fixture fixture = fixture(21001L, new FakePaymentProvider(CLOCK));
        FakePaymentProvider providerB = new FakePaymentProvider(CLOCK);
        PreparedPaymentVerification expected = prepare(fixture.telegramId);

        verificationTransactions.manualReview(expected, "PROVIDER_PAYMENT_NOT_FOUND", NOW);
        assertThat(read(fixture.orderId).getStatus())
                .isEqualTo(PaymentStatus.MANUAL_REVIEW_REQUIRED);

        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                recovery(providerB).resetLostPayment(1L, fixture.telegramId));
        PaymentOrder reread = read(fixture.orderId);
        assertThat(reread.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(reread.getActivationStatus()).isEqualTo(PaymentActivationStatus.NOT_READY);
        assertThat(reread.getSafeFailureCode()).isEqualTo("FAKE_PROVIDER_STATE_LOST");
        assertThat(reread.getPaidAt()).isNull();
    }

    @Test
    void resetIsForbiddenWhenPaymentStillExistsAfterRestartCheck() {
        FakePaymentProvider providerA = new FakePaymentProvider(CLOCK);
        Fixture fixture = fixture(21002L, providerA);
        PreparedPaymentVerification expected = prepare(fixture.telegramId);
        verificationTransactions.manualReview(expected, "PROVIDER_PAYMENT_NOT_FOUND", NOW);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(tx -> recovery(providerA)
                        .resetLostPayment(1L, fixture.telegramId)))
                .isInstanceOf(FakePaymentStillExistsException.class);

        PaymentOrder reread = read(fixture.orderId);
        assertThat(reread.getStatus()).isEqualTo(PaymentStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(reread.getSafeFailureCode()).isEqualTo("PROVIDER_PAYMENT_NOT_FOUND");
    }

    @Test
    void resetDoesNotUseFakeProviderForNonFakeOrder() {
        Fixture fixture = fixture(21003L, new FakePaymentProvider(CLOCK));
        PreparedPaymentVerification expected = prepare(fixture.telegramId);
        verificationTransactions.manualReview(expected, "PROVIDER_PAYMENT_NOT_FOUND", NOW);
        jdbc.update("update payment_orders set provider = 'YOOKASSA' where id = ?",
                fixture.orderId);
        entityManager.clear();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(tx -> recovery(new FakePaymentProvider(CLOCK))
                        .resetLostPayment(1L, fixture.telegramId)))
                .isInstanceOf(PaymentNotFoundException.class);
        assertThat(read(fixture.orderId).getStatus())
                .isEqualTo(PaymentStatus.MANUAL_REVIEW_REQUIRED);
    }

    @Test
    void concurrentResetHasSingleTerminalTransition() throws Exception {
        Fixture fixture = fixture(21004L, new FakePaymentProvider(CLOCK));
        PreparedPaymentVerification expected = prepare(fixture.telegramId);
        verificationTransactions.manualReview(expected, "PROVIDER_PAYMENT_NOT_FOUND", NOW);
        FakePaymentRecoveryService recovery = recovery(new FakePaymentProvider(CLOCK));
        var executor = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            Future<?> first = executor.submit(() -> concurrentReset(recovery, fixture.telegramId, gate));
            Future<?> second = executor.submit(() -> concurrentReset(recovery, fixture.telegramId, gate));
            gate.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        PaymentOrder reread = read(fixture.orderId);
        assertThat(reread.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(reread.getSafeFailureCode()).isEqualTo("FAKE_PROVIDER_STATE_LOST");
    }

    @ParameterizedTest
    @MethodSource("nonStateLossReasons")
    void resetIsForbiddenForOtherManualReviewReasons(String reason) {
        long telegramId = 21100L + Math.abs(reason.hashCode() % 500);
        Fixture fixture = fixture(telegramId, new FakePaymentProvider(CLOCK));
        PreparedPaymentVerification expected = prepare(telegramId);
        verificationTransactions.manualReview(expected, reason, NOW);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(tx -> recovery(new FakePaymentProvider(CLOCK))
                        .resetLostPayment(1L, telegramId)))
                .isInstanceOf(PaymentNotFoundException.class);
        PaymentOrder reread = read(fixture.orderId);
        assertThat(reread.getStatus()).isEqualTo(PaymentStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(reread.getSafeFailureCode()).isEqualTo(reason);
    }

    @Test
    void checkoutIsBlockedBeforeResetAndCreatesNewOrderAfterStateLoss() {
        FakePaymentProvider providerA = new FakePaymentProvider(CLOCK);
        Fixture fixture = fixture(21200L, providerA);
        PreparedPaymentVerification expected = prepare(fixture.telegramId);
        verificationTransactions.manualReview(expected, "PROVIDER_PAYMENT_NOT_FOUND", NOW);
        PaymentCheckoutService checkout = checkoutService(new FakePaymentProvider(CLOCK));

        assertThatThrownBy(() -> checkout.startCheckout(fixture.telegramId, fixture.tariffCode))
                .isInstanceOf(RuntimeException.class);

        FakePaymentProvider providerB = new FakePaymentProvider(CLOCK);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                recovery(providerB).resetLostPayment(1L, fixture.telegramId));

        var created = checkoutService(providerB).startCheckout(
                fixture.telegramId, fixture.tariffCode);
        PaymentOrder oldOrder = read(fixture.orderId);
        PaymentOrder newOrder = orderRepository.findById(created.paymentOrderId()).orElseThrow();

        assertThat(oldOrder.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(oldOrder.getSafeFailureCode()).isEqualTo("FAKE_PROVIDER_STATE_LOST");
        assertThat(newOrder.getId()).isNotEqualTo(oldOrder.getId());
        assertThat(newOrder.getIdempotenceKey()).isNotEqualTo(oldOrder.getIdempotenceKey());
        assertThat(newOrder.getProviderPaymentId()).isNotEqualTo(oldOrder.getProviderPaymentId());
        assertThat(newOrder.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(providerB.getPayment(newOrder.getProviderPaymentId()).providerPaymentId())
                .isEqualTo(newOrder.getProviderPaymentId());
    }

    @ParameterizedTest
    @MethodSource("unexpectedProviderExceptions")
    void unexpectedProviderExceptionDoesNotConfirmStateLoss(
            Supplier<? extends RuntimeException> exceptionSupplier
    ) {
        Fixture fixture = fixture(21300L + Math.abs(exceptionSupplier.get().hashCode() % 500),
                new FakePaymentProvider(CLOCK));
        PreparedPaymentVerification expected = prepare(fixture.telegramId);
        verificationTransactions.manualReview(expected, "PROVIDER_PAYMENT_NOT_FOUND", NOW);
        FakePaymentProvider failingProvider = new FakePaymentProvider(CLOCK) {
            @Override
            public ru.murad.myvpn.dto.ProviderPayment getPayment(String providerPaymentId) {
                throw exceptionSupplier.get();
            }
        };

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(tx -> recovery(failingProvider)
                        .resetLostPayment(1L, fixture.telegramId)))
                .isInstanceOf(RuntimeException.class);
        PaymentOrder reread = read(fixture.orderId);
        assertThat(reread.getStatus()).isEqualTo(PaymentStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(reread.getSafeFailureCode()).isEqualTo("PROVIDER_PAYMENT_NOT_FOUND");
    }

    static Stream<Arguments> unexpectedProviderExceptions() {
        return Stream.of(
                Arguments.of((Supplier<RuntimeException>) () ->
                        new PaymentProviderUncertainException("temporary provider failure")),
                Arguments.of((Supplier<RuntimeException>) () ->
                        new PaymentProviderPermanentException("provider configuration failure")),
                Arguments.of((Supplier<RuntimeException>) () ->
                        new IllegalStateException("unexpected provider failure")));
    }

    static Stream<String> nonStateLossReasons() {
        return Stream.of(
                "PROVIDER_PAYMENT_ID_MISMATCH",
                "PAYMENT_AMOUNT_MISMATCH",
                "PAYMENT_CURRENCY_MISMATCH",
                "PAYMENT_METADATA_MISMATCH",
                "PAYMENT_METHOD_MISMATCH",
                "PROVIDER_STATUS_PAID_MISMATCH",
                "PROVIDER_TIMESTAMP_MISMATCH");
    }

    private void concurrentReset(
            FakePaymentRecoveryService recovery,
            long telegramId,
            CountDownLatch gate
    ) {
        try {
            gate.await(20, TimeUnit.SECONDS);
            new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                    recovery.resetLostPayment(1L, telegramId));
        } catch (PaymentNotFoundException ignored) {
            // The second transaction may safely observe the already FAILED order.
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private FakePaymentRecoveryService recovery(FakePaymentProvider provider) {
        return new FakePaymentRecoveryServiceImpl(
                administratorId -> { }, userRepository, orderRepository, CLOCK, provider,
                entityManager);
    }

    private PaymentCheckoutService checkoutService(FakePaymentProvider provider) {
        return new PaymentCheckoutServiceImpl(
                userRepository, orderRepository,
                new PaymentProviderRegistry(Optional.of(provider)), checkoutTransactions,
                paymentProperties, CLOCK);
    }

    private PreparedPaymentVerification prepare(long telegramId) {
        return verificationTransactions.prepare(telegramId, NOW, Duration.ofSeconds(5)).prepared();
    }

    private PaymentOrder read(UUID id) {
        entityManager.clear();
        return orderRepository.findById(id).orElseThrow();
    }

    private Fixture fixture(long telegramId, FakePaymentProvider provider) {
        TelegramUser user = userRepository.saveAndFlush(TelegramUser.builder()
                .id(UUID.randomUUID()).telegramId(telegramId).chatId(telegramId)
                .role(UserRole.USER).createdAt(NOW).updatedAt(NOW).build());
        VpnTariff tariff = tariffRepository.saveAndFlush(VpnTariff.builder()
                .id(UUID.randomUUID()).code("RESTART_" + telegramId).name("Restart tariff")
                .durationDays(30).price(new BigDecimal("100.00")).currency("RUB")
                .active(true).createdAt(NOW).updatedAt(NOW).build());
        PaymentOrder order = PaymentOrder.create(user, tariff, PaymentProviderType.FAKE,
                NOW, Duration.ofHours(1));
        order.markCreating(NOW);
        orderRepository.saveAndFlush(order);
        var created = provider.createPayment(new CreatePaymentCommand(
                order.getId(), order.getIdempotenceKey(), order.getAmount(), order.getCurrency(),
                "restart", URI.create("https://example.invalid/return"),
                Map.of("payment_order_id", order.getId().toString())));
        order.markPending(created.providerPaymentId(), created.confirmationUrl().toString(),
                created.providerCreatedAt(), created.expiresAt(), NOW);
        orderRepository.saveAndFlush(order);
        return new Fixture(telegramId, order.getId(), tariff.getCode());
    }

    private record Fixture(long telegramId, UUID orderId, String tariffCode) { }
}
