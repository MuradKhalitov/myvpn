package ru.murad.myvpn.service.impl;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
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
import ru.murad.myvpn.dto.PaymentVerificationOutcome;
import ru.murad.myvpn.dto.PaymentVerificationResult;
import ru.murad.myvpn.dto.PaymentCheckoutResult;
import ru.murad.myvpn.dto.PreparedPaymentVerification;
import ru.murad.myvpn.dto.ProviderPayment;
import ru.murad.myvpn.exception.PaymentOrderValidationException;
import ru.murad.myvpn.exception.ProviderPaymentValidationException;
import ru.murad.myvpn.client.FakePaymentProvider;
import ru.murad.myvpn.model.PaymentActivationStatus;
import ru.murad.myvpn.model.PaymentOrder;
import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.model.PaymentStatus;
import ru.murad.myvpn.model.ProviderPaymentStatus;
import ru.murad.myvpn.model.TelegramUser;
import ru.murad.myvpn.model.UserRole;
import ru.murad.myvpn.model.VpnTariff;
import ru.murad.myvpn.repository.PaymentOrderRepository;
import ru.murad.myvpn.repository.TelegramUserRepository;
import ru.murad.myvpn.repository.VpnTariffRepository;
import ru.murad.myvpn.service.ProviderPaymentValidator;
import ru.murad.myvpn.service.PaymentVerificationService;
import ru.murad.myvpn.service.PaymentVerificationTransactionService;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "vpn.lifecycle.expiration-check-delay=3600000",
        "vpn.lifecycle.pending-recovery-delay=3600000",
        "payment.verification.min-interval=5s"
})
@ActiveProfiles("test")
@Testcontainers
@Import(PaymentVerificationTransactionServiceIntegrationTest.FixedClockConfiguration.class)
class PaymentVerificationTransactionServiceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-25T12:00:00Z");
    private static final Duration INTERVAL = Duration.ofSeconds(5);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String OPEN_INDEX = "uk_payment_order_open_user";

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return CLOCK;
        }
    }

    @Container
    static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer<>("postgres:16.3-alpine");

    @DynamicPropertySource
    static void configurePostgresql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
    }

    @Autowired private PaymentVerificationTransactionService verificationService;
    @Autowired private PaymentVerificationService verificationFacade;
    @Autowired private ru.murad.myvpn.service.PaymentCheckoutService checkoutService;
    @Autowired private ProviderPaymentValidator providerPaymentValidator;
    @Autowired private PaymentOrderRepository orderRepository;
    @Autowired private TelegramUserRepository userRepository;
    @Autowired private VpnTariffRepository tariffRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @SpyBean private FakePaymentProvider fakePaymentProvider;

    @BeforeEach
    void cleanDatabase() {
        orderRepository.deleteAll();
        userRepository.deleteAll();
        entityManager.clear();
    }

    @Test
    void noOrdersReturnsNotFoundAndCreatesNothing() {
        TelegramUser user = user(10001L);

        var result = verificationService.prepare(user.getTelegramId(), CLOCK.instant(), INTERVAL);

        assertThat(result.prepared()).isNull();
        assertThat(result.immediateResult().outcome()).isEqualTo(PaymentVerificationOutcome.NOT_FOUND);
        assertThat(orderRepository.count()).isZero();
    }

    @Test
    void pendingOrderIsReservedAndContainsCompleteImmutableSnapshot() {
        PaymentOrder order = pendingOrder(10002L, "pending-1");

        var result = verificationService.prepare(10002L, NOW, INTERVAL);

        assertThat(result.immediateResult()).isNull();
        PreparedPaymentVerification snapshot = result.prepared();
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.paymentOrderId()).isEqualTo(order.getId());
        assertThat(snapshot.userId()).isEqualTo(order.getUser().getId());
        assertThat(snapshot.provider()).isEqualTo(order.getProvider());
        assertThat(snapshot.providerPaymentId()).isEqualTo("pending-1");
        assertThat(snapshot.amount()).isEqualByComparingTo(order.getAmount());
        assertThat(snapshot.currency()).isEqualTo(order.getCurrency());
        assertThat(snapshot.tariffId()).isEqualTo(order.getTariff().getId());
        assertThat(snapshot.tariffCodeSnapshot()).isEqualTo(order.getTariffCodeSnapshot());
        assertThat(snapshot.tariffNameSnapshot()).isEqualTo(order.getTariffNameSnapshot());
        assertThat(snapshot.durationDaysSnapshot()).isEqualTo(order.getDurationDaysSnapshot());
        assertThat(snapshot.currentPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(snapshot.idempotenceKey()).isEqualTo(order.getIdempotenceKey());

        entityManager.clear();
        PaymentOrder reread = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(reread.getVerificationAttempts()).isEqualTo(1);
        assertThat(reread.getNextVerificationAt()).isEqualTo(NOW.plus(INTERVAL));
    }

    @Test
    void creatingOrderWithProviderIdIsAllowedByCurrentPolicy() {
        PaymentOrder order = creatingOrder(10003L);
        setProviderId(order, "creating-with-id");

        var result = verificationService.prepare(10003L, NOW, INTERVAL);

        assertThat(result.prepared()).isNotNull();
        assertThat(result.prepared().providerPaymentId()).isEqualTo("creating-with-id");
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getVerificationAttempts()).isEqualTo(1);
    }

    @Test
    void creatingWithoutProviderIdIsCheckoutIncompleteWithoutMutation() {
        PaymentOrder order = creatingOrder(10004L);
        Instant updatedAt = order.getUpdatedAt();

        var result = verificationService.prepare(10004L, NOW, INTERVAL);

        assertThat(result.immediateResult().outcome()).isEqualTo(PaymentVerificationOutcome.CHECKOUT_INCOMPLETE);
        assertUnchanged(order.getId(), order, updatedAt);
    }

    @ParameterizedTest
    @MethodSource("manualReviewCodes")
    void manualReviewIsImmediateAndDoesNotMutate(String code) {
        long telegramId = 10005L + code.hashCode();
        PaymentOrder order = pendingOrder(telegramId, "manual-" + code);
        order.markPaymentManualReviewRequired(code, NOW.plusSeconds(1));
        orderRepository.saveAndFlush(order);
        Instant updatedAt = order.getUpdatedAt();

        var result = verificationService.prepare(telegramId, NOW, INTERVAL);

        assertThat(result.immediateResult().outcome()).isEqualTo(PaymentVerificationOutcome.MANUAL_REVIEW_REQUIRED);
        assertUnchanged(order.getId(), order, updatedAt);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getSafeFailureCode()).isEqualTo(code);
    }

    static Stream<Arguments> manualReviewCodes() {
        return Stream.of(arguments("PROVIDER_PAYMENT_NOT_FOUND"), arguments("PAYMENT_AMOUNT_MISMATCH"));
    }

    @ParameterizedTest
    @MethodSource("terminalStatuses")
    void failedAndExpiredAreTerminalWithoutMutation(PaymentStatus status) {
        long telegramId = 10010L + status.ordinal();
        PaymentOrder order = terminalOrder(telegramId, status);
        Instant updatedAt = order.getUpdatedAt();

        var result = verificationService.prepare(telegramId, NOW, INTERVAL);

        assertThat(result.immediateResult().outcome()).isEqualTo(PaymentVerificationOutcome.TERMINAL);
        assertUnchanged(order.getId(), order, updatedAt);
    }

    static Stream<Arguments> terminalStatuses() {
        return Stream.of(arguments(PaymentStatus.FAILED), arguments(PaymentStatus.EXPIRED));
    }

    @Test
    void succeededIsAlreadySucceededWithoutMutation() {
        PaymentOrder order = terminalOrder(10020L, PaymentStatus.SUCCEEDED);
        Instant updatedAt = order.getUpdatedAt();

        var result = verificationService.prepare(10020L, NOW, INTERVAL);

        assertThat(result.immediateResult().outcome()).isEqualTo(PaymentVerificationOutcome.ALREADY_SUCCEEDED);
        assertUnchanged(order.getId(), order, updatedAt);
    }

    @Test
    void canceledIsAlreadyCanceledWithoutMutation() {
        PaymentOrder order = pendingOrder(10021L, "canceled");
        order.markCanceled(NOW.plusSeconds(1));
        orderRepository.saveAndFlush(order);
        Instant updatedAt = order.getUpdatedAt();

        var result = verificationService.prepare(10021L, NOW, INTERVAL);

        assertThat(result.immediateResult().outcome()).isEqualTo(PaymentVerificationOutcome.ALREADY_CANCELED);
        assertUnchanged(order.getId(), order, updatedAt);
    }

    @Test
    void blockingOrderHasPriorityOverSucceededHistory() {
        TelegramUser user = user(10022L);
        terminalOrder(user, PaymentStatus.SUCCEEDED, NOW.minusSeconds(20));
        PaymentOrder pending = pendingOrder(user, "new-pending", NOW.minusSeconds(10));

        var result = verificationService.prepare(10022L, NOW, INTERVAL);

        assertThat(result.prepared().paymentOrderId()).isEqualTo(pending.getId());
    }

    @Test
    void blockingCreatingOrderHasPriorityOverFailedHistory() {
        TelegramUser user = user(10023L);
        terminalOrder(user, PaymentStatus.FAILED, NOW.minusSeconds(20));
        PaymentOrder creating = creatingOrder(user, NOW.minusSeconds(10));
        setProviderId(creating, "creating-priority");

        var result = verificationService.prepare(10023L, NOW, INTERVAL);

        assertThat(result.prepared().paymentOrderId()).isEqualTo(creating.getId());
    }

    @Test
    void ambiguousRelevantOrdersReturnAmbiguousWithoutSelectingOrMutatingEither() {
        TelegramUser user = user(10024L);
        jdbcTemplate.execute("DROP INDEX " + OPEN_INDEX);
        PaymentOrder first = null;
        PaymentOrder second = null;
        try {
            first = pendingOrder(user, "ambiguous-pending", NOW.minusSeconds(20));
            second = creatingOrder(user, NOW.minusSeconds(10));
            setProviderId(second, "ambiguous-creating");
            int firstAttempts = first.getVerificationAttempts();
            int secondAttempts = second.getVerificationAttempts();
            Instant firstUpdated = first.getUpdatedAt();
            Instant secondUpdated = second.getUpdatedAt();

            var result = verificationService.prepare(10024L, NOW, INTERVAL);

            assertThat(result.immediateResult().outcome()).isEqualTo(PaymentVerificationOutcome.AMBIGUOUS_PAYMENT_STATE);
            assertThat(result.prepared()).isNull();
            assertThat(orderRepository.findById(first.getId()).orElseThrow().getVerificationAttempts()).isEqualTo(firstAttempts);
            assertThat(orderRepository.findById(second.getId()).orElseThrow().getVerificationAttempts()).isEqualTo(secondAttempts);
            assertThat(orderRepository.findById(first.getId()).orElseThrow().getUpdatedAt()).isEqualTo(firstUpdated);
            assertThat(orderRepository.findById(second.getId()).orElseThrow().getUpdatedAt()).isEqualTo(secondUpdated);
        } finally {
            if (first != null && second != null) {
                jdbcTemplate.update("DELETE FROM payment_orders WHERE id IN (?, ?)", first.getId(), second.getId());
            }
            jdbcTemplate.execute("CREATE UNIQUE INDEX " + OPEN_INDEX
                    + " ON payment_orders (user_id) WHERE status IN ('NEW', 'CREATING', 'PENDING', 'MANUAL_REVIEW_REQUIRED')");
        }
    }

    @ParameterizedTest
    @MethodSource("cooldownCases")
    void cooldownBoundariesAreComparedInclusively(Duration offset, long telegramId, boolean allowed) {
        PaymentOrder order = pendingOrder(telegramId, "cooldown-" + telegramId);
        Instant beforeUpdated = order.getUpdatedAt();
        Instant next = NOW.plus(offset);
        jdbcTemplate.update("UPDATE payment_orders SET next_verification_at = ? WHERE id = ?",
                Timestamp.from(next), order.getId());
        entityManager.clear();

        var result = verificationService.prepare(telegramId, NOW, INTERVAL);
        PaymentOrder reread = reread(order.getId());

        if (allowed) {
            assertThat(result.prepared()).isNotNull();
            assertThat(reread.getVerificationAttempts()).isEqualTo(1);
            assertThat(reread.getNextVerificationAt()).isEqualTo(NOW.plus(INTERVAL));
        } else {
            assertThat(result.immediateResult().outcome()).isEqualTo(PaymentVerificationOutcome.TOO_EARLY);
            assertThat(reread.getVerificationAttempts()).isZero();
            assertThat(reread.getNextVerificationAt()).isEqualTo(next);
            assertThat(reread.getUpdatedAt()).isEqualTo(beforeUpdated);
        }
    }

    static Stream<Arguments> cooldownCases() {
        return Stream.of(arguments(Duration.ofSeconds(1), 10031L, false),
                arguments(Duration.ZERO, 10032L, true),
                arguments(Duration.ofNanos(-1_000), 10033L, true));
    }

    @Test
    void nullNextVerificationAtAllowsReservation() {
        PaymentOrder order = pendingOrder(10040L, "cooldown-null");
        assertThat(order.getNextVerificationAt()).isNull();

        var result = verificationService.prepare(10040L, NOW, INTERVAL);

        assertThat(result.prepared()).isNotNull();
        assertThat(reread(order.getId()).getNextVerificationAt()).isEqualTo(NOW.plus(INTERVAL));
    }

    @Test
    void repeatedRequestDuringCooldownReservesOnlyOnce() {
        PaymentOrder order = pendingOrder(10041L, "repeat");

        var first = verificationService.prepare(10041L, NOW, INTERVAL);
        var second = verificationService.prepare(10041L, NOW, INTERVAL);

        assertThat(first.prepared()).isNotNull();
        assertThat(second.immediateResult().outcome()).isEqualTo(PaymentVerificationOutcome.TOO_EARLY);
        PaymentOrder reread = reread(order.getId());
        assertThat(reread.getVerificationAttempts()).isEqualTo(1);
        assertThat(reread.getNextVerificationAt()).isEqualTo(NOW.plus(INTERVAL));
    }

    @Test
    void maxValueMinusOneCanBeReserved() {
        PaymentOrder order = pendingOrder(10042L, "max-minus-one");
        jdbcTemplate.update("UPDATE payment_orders SET verification_attempts = ?, next_verification_at = ? WHERE id = ?",
                Integer.MAX_VALUE - 1, Timestamp.from(NOW.minusSeconds(1)), order.getId());
        entityManager.clear();

        var result = verificationService.prepare(10042L, NOW, INTERVAL);

        assertThat(result.prepared()).isNotNull();
        assertThat(reread(order.getId()).getVerificationAttempts()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void maxValueOverflowIsControlledAndRollsBackAllMutation() {
        PaymentOrder order = pendingOrder(10043L, "max");
        jdbcTemplate.update("UPDATE payment_orders SET verification_attempts = ?, next_verification_at = ?, updated_at = ? WHERE id = ?",
                Integer.MAX_VALUE, Timestamp.from(NOW.minusSeconds(1)), Timestamp.from(NOW.minusSeconds(2)), order.getId());
        entityManager.clear();
        PaymentOrder before = reread(order.getId());

        assertThatThrownBy(() -> verificationService.prepare(10043L, NOW, INTERVAL))
                .isInstanceOf(PaymentOrderValidationException.class);

        entityManager.clear();
        PaymentOrder after = reread(order.getId());
        assertThat(after.getVerificationAttempts()).isEqualTo(Integer.MAX_VALUE);
        assertThat(after.getNextVerificationAt()).isEqualTo(before.getNextVerificationAt());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
    }

    @Test
    void trustedPendingProviderResultRemainsPendingWithoutActivationSideEffects() {
        PreparedPaymentVerification expected = trustedExpected(11001L);
        ProviderPayment actual = providerPayment(expected, ProviderPaymentStatus.PENDING, null);

        var result = applyTrusted(expected, actual);
        PaymentOrder order = reread(expected.paymentOrderId());

        assertThat(result.outcome()).isEqualTo(PaymentVerificationOutcome.STILL_PENDING);
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(order.getActivationStatus()).isEqualTo(PaymentActivationStatus.NOT_READY);
        assertThat(order.getPaidAt()).isNull();
        assertThat(order.getSafeFailureCode()).isNull();
        assertThat(order.getProviderPaymentId()).isEqualTo(expected.providerPaymentId());
        assertThat(subscriptionCount()).isZero();
        assertThat(vpnAccessCount()).isZero();
    }

    @Test
    void trustedSuccessUsesProviderPaidAtWithPostgresMicrosPrecision() {
        PreparedPaymentVerification expected = trustedExpected(11002L);
        Instant createdAt = NOW.minusSeconds(1).plusNanos(987_654_321);
        Instant paidAt = Instant.parse("2026-07-25T12:00:00.123456789Z");
        ProviderPayment actual = providerPayment(expected, ProviderPaymentStatus.SUCCEEDED,
                paidAt, createdAt);

        var result = applyTrusted(expected, actual);
        PaymentOrder order = reread(expected.paymentOrderId());

        assertThat(result.outcome()).isEqualTo(PaymentVerificationOutcome.SUCCEEDED);
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(order.getActivationStatus()).isEqualTo(PaymentActivationStatus.PENDING);
        assertThat(order.getPaidAt()).isEqualTo(Instant.parse("2026-07-25T12:00:00.123456Z"));
        assertThat(order.getActivationTargetExpiresAt()).isNull();
        assertThat(order.getSafeFailureCode()).isNull();
        assertThat(order.getPaidAt()).isNotEqualTo(NOW);
        assertThat(subscriptionCount()).isZero();
        assertThat(vpnAccessCount()).isZero();
    }

    @Test
    void duplicateSuccessIsIdempotentAndDoesNotOverwriteAuthoritativePaidAt() {
        PreparedPaymentVerification expected = trustedExpected(11003L);
        Instant firstPaidAt = Instant.parse("2026-07-25T12:00:00.123456789Z");
        applyTrusted(expected, providerPayment(expected, ProviderPaymentStatus.SUCCEEDED, firstPaidAt));
        PaymentOrder before = reread(expected.paymentOrderId());

        var duplicate = verificationService.apply(expected,
                providerPayment(expected, ProviderPaymentStatus.SUCCEEDED,
                        Instant.parse("2026-07-25T12:00:01.999999999Z")), NOW.plusSeconds(1));
        PaymentOrder after = reread(expected.paymentOrderId());

        assertThat(duplicate.outcome()).isEqualTo(PaymentVerificationOutcome.ALREADY_SUCCEEDED);
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(after.getActivationStatus()).isEqualTo(before.getActivationStatus());
        assertThat(after.getPaidAt()).isEqualTo(before.getPaidAt());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(after.getSafeFailureCode()).isNull();
    }

    @Test
    void trustedCanceledAndDuplicateCanceledRemainTerminalWithoutSideEffects() {
        PreparedPaymentVerification expected = trustedExpected(11004L);
        ProviderPayment actual = providerPayment(expected, ProviderPaymentStatus.CANCELED, null);

        var first = applyTrusted(expected, actual);
        PaymentOrder before = reread(expected.paymentOrderId());
        var duplicate = verificationService.apply(expected, actual, NOW.plusSeconds(1));
        PaymentOrder after = reread(expected.paymentOrderId());

        assertThat(first.outcome()).isEqualTo(PaymentVerificationOutcome.CANCELED);
        assertThat(duplicate.outcome()).isEqualTo(PaymentVerificationOutcome.ALREADY_CANCELED);
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(after.getActivationStatus()).isEqualTo(PaymentActivationStatus.NOT_READY);
        assertThat(after.getPaidAt()).isNull();
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(subscriptionCount()).isZero();
        assertThat(vpnAccessCount()).isZero();
    }

    @Test
    void unknownProviderResultIsUncertainAndDoesNotMutateOrder() {
        PreparedPaymentVerification expected = trustedExpected(11005L);
        ProviderPayment actual = providerPayment(expected, ProviderPaymentStatus.UNKNOWN, null);

        var result = verificationService.apply(expected, actual, NOW);
        PaymentOrder order = reread(expected.paymentOrderId());

        assertThat(result.outcome()).isEqualTo(PaymentVerificationOutcome.PROVIDER_RESULT_UNCERTAIN);
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(order.getActivationStatus()).isEqualTo(PaymentActivationStatus.NOT_READY);
        assertThat(order.getPaidAt()).isNull();
        assertThat(order.getSafeFailureCode()).isNull();
    }

    @ParameterizedTest
    @MethodSource("providerMismatchCases")
    void providerMismatchMovesOrderToManualReviewWithoutPartialMutation(
            String expectedCode, UnaryOperator<ProviderPayment> mutation) {
        PreparedPaymentVerification expected = trustedExpected(11100L + expectedCode.hashCode());
        PaymentOrder before = reread(expected.paymentOrderId());
        ProviderPayment actual = mutation.apply(providerPayment(expected, ProviderPaymentStatus.PENDING, null));

        var result = validateAndReview(expected, actual);
        PaymentOrder after = reread(expected.paymentOrderId());

        assertManualReview(result, after, expectedCode, before);
    }

    static Stream<Arguments> providerMismatchCases() {
        return Stream.of(
                arguments("PROVIDER_PAYMENT_ID_MISMATCH", (UnaryOperator<ProviderPayment>) p -> with(p, p.providerPaymentId() + "-other")),
                arguments("PAYMENT_AMOUNT_MISMATCH", (UnaryOperator<ProviderPayment>) p -> with(p, new BigDecimal("100.01"), null, null, null, null, null)),
                arguments("PAYMENT_CURRENCY_MISMATCH", (UnaryOperator<ProviderPayment>) p -> with(p, null, null, "USD", null, null, null)),
                arguments("PAYMENT_METADATA_MISMATCH", (UnaryOperator<ProviderPayment>) p -> with(p, null, null, null, UUID.randomUUID(), null, null)),
                arguments("PAYMENT_METADATA_MISMATCH", (UnaryOperator<ProviderPayment>) PaymentVerificationTransactionServiceIntegrationTest::withoutMetadata),
                arguments("PAYMENT_METHOD_MISMATCH", (UnaryOperator<ProviderPayment>) p -> with(p, null, null, null, null, "card", null)),
                arguments("PROVIDER_STATUS_PAID_MISMATCH", (UnaryOperator<ProviderPayment>) p -> with(p, null, true, null, null, null, null)),
                arguments("PROVIDER_TIMESTAMP_MISMATCH", (UnaryOperator<ProviderPayment>) p -> with(p, null, null, null, null, null, NOW.plus(Duration.ofHours(1))))
        );
    }

    @Test
    void amountComparisonAcceptsScaleDifference() {
        PreparedPaymentVerification expected = trustedExpected(11201L);
        ProviderPayment actual = with(providerPayment(expected, ProviderPaymentStatus.PENDING, null),
                new BigDecimal("100.0"), null, null, null, null, null);

        var result = applyTrusted(expected, actual);

        assertThat(result.outcome()).isEqualTo(PaymentVerificationOutcome.STILL_PENDING);
        assertThat(reread(expected.paymentOrderId()).getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void currencyMismatchVariantsAreAllManualReview() {
        for (String currency : List.of("USD", "rub", "")) {
            PreparedPaymentVerification expected = trustedExpected(11210L + currency.hashCode());
            ProviderPayment actual = with(providerPayment(expected, ProviderPaymentStatus.PENDING, null),
                    null, null, currency, null, null, null);
            var result = validateAndReview(expected, actual);
            assertThat(result.outcome()).isEqualTo(PaymentVerificationOutcome.MANUAL_REVIEW_REQUIRED);
            assertThat(reread(expected.paymentOrderId()).getSafeFailureCode())
                    .isEqualTo("PAYMENT_CURRENCY_MISMATCH");
        }
    }

    @Test
    void statusPaidMismatchVariantsAreManualReview() {
        List<ProviderPaymentStatus> statuses = List.of(ProviderPaymentStatus.PENDING,
                ProviderPaymentStatus.SUCCEEDED, ProviderPaymentStatus.CANCELED);
        for (int i = 0; i < statuses.size(); i++) {
            PreparedPaymentVerification expected = providerExpected(11301L + i);
            ProviderPaymentStatus status = statuses.get(i);
            boolean paid = i != 1;
            ProviderPayment actual = with(providerPayment(expected, status,
                    status == ProviderPaymentStatus.SUCCEEDED ? null : null),
                    null, paid, null, null, null, null);
            var result = validateAndReview(expected, actual);
            assertThat(result.outcome()).isEqualTo(PaymentVerificationOutcome.MANUAL_REVIEW_REQUIRED);
            assertThat(reread(expected.paymentOrderId()).getSafeFailureCode())
                    .isEqualTo("PROVIDER_STATUS_PAID_MISMATCH");
        }
    }

    @Test
    void timestampMismatchVariantsAreManualReview() {
        for (int i = 0; i < 6; i++) {
            PreparedPaymentVerification expected = providerExpected(11401L + i);
            ProviderPayment actual = switch (i) {
                case 0 -> providerPayment(expected, ProviderPaymentStatus.PENDING, null,
                        NOW.plus(Duration.ofHours(1)));
                case 1 -> providerPayment(expected, ProviderPaymentStatus.SUCCEEDED, null);
                case 2 -> providerPayment(expected, ProviderPaymentStatus.SUCCEEDED,
                        NOW.minusSeconds(2), NOW.minusSeconds(1));
                case 3 -> providerPayment(expected, ProviderPaymentStatus.SUCCEEDED,
                        NOW.plus(Duration.ofHours(1)), NOW);
                case 4 -> providerPayment(expected, ProviderPaymentStatus.PENDING,
                        NOW, NOW.minusSeconds(1));
                default -> providerPayment(expected, ProviderPaymentStatus.CANCELED,
                        NOW, NOW.minusSeconds(1));
            };
            var result = validateAndReview(expected, actual);
            assertThat(result.outcome()).isEqualTo(PaymentVerificationOutcome.MANUAL_REVIEW_REQUIRED);
            assertThat(reread(expected.paymentOrderId()).getSafeFailureCode())
                    .isEqualTo("PROVIDER_TIMESTAMP_MISMATCH");
        }
    }

    @Test
    void paymentNotFoundBecomesManualReviewAndRemainsIdempotent() {
        PaymentOrder order = pendingOrder(11501L, "missing-from-fake-provider");

        var first = verificationFacade.verifyCurrentPayment(11501L);
        PaymentOrder afterFirst = reread(order.getId());
        var second = verificationFacade.verifyCurrentPayment(11501L);
        PaymentOrder afterSecond = reread(order.getId());

        assertThat(first.outcome()).isEqualTo(PaymentVerificationOutcome.MANUAL_REVIEW_REQUIRED);
        assertThat(second.outcome()).isEqualTo(PaymentVerificationOutcome.MANUAL_REVIEW_REQUIRED);
        assertThat(afterFirst.getStatus()).isEqualTo(PaymentStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(afterFirst.getSafeFailureCode()).isEqualTo("PROVIDER_PAYMENT_NOT_FOUND");
        assertThat(afterSecond.getSafeFailureCode()).isEqualTo(afterFirst.getSafeFailureCode());
        assertThat(afterSecond.getPaidAt()).isNull();
        assertThat(afterSecond.getActivationStatus()).isEqualTo(PaymentActivationStatus.NOT_READY);
    }

    @ParameterizedTest
    @MethodSource("snapshotMutations")
    void staleTx2SnapshotIsRejectedWithoutProviderDrivenMutation(
            String field, SnapshotMutation mutation) {
        PreparedPaymentVerification expected = trustedExpected(11600L + field.hashCode());
        PaymentOrder before = reread(expected.paymentOrderId());
        PreparedPaymentVerification staleExpected = mutation.apply(expected, this);
        ProviderPayment actual = providerPayment(expected, ProviderPaymentStatus.SUCCEEDED,
                NOW.plusNanos(123_456_789));

        PaymentVerificationResult result;
        try {
            result = verificationService.apply(staleExpected, actual, NOW);
        } finally {
            if (field.equals("currency")) {
                restoreCurrencyConstraint(expected.paymentOrderId());
            }
        }
        PaymentOrder after = reread(expected.paymentOrderId());
        String rendered = String.valueOf(result);

        assertThat(result.outcome()).isEqualTo(PaymentVerificationOutcome.PROVIDER_RESULT_UNCERTAIN);
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(after.getActivationStatus()).isEqualTo(PaymentActivationStatus.NOT_READY);
        assertThat(after.getPaidAt()).isNull();
        assertThat(after.getSafeFailureCode()).isNull();
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(rendered).doesNotContain(expected.paymentOrderId().toString(), expected.userId().toString(),
                expected.providerPaymentId(), expected.idempotenceKey().toString(), expected.currency());
    }

    static Stream<Arguments> snapshotMutations() {
        return Stream.of(
                arguments("user_id", (SnapshotMutation) (e, t) -> t.mutateUser(e)),
                arguments("provider", (SnapshotMutation) (e, t) -> t.mutateProvider(e)),
                arguments("provider_payment_id", (SnapshotMutation) (e, t) -> t.mutateProviderPaymentId(e)),
                arguments("amount", (SnapshotMutation) (e, t) -> t.mutateAmount(e)),
                arguments("currency", (SnapshotMutation) (e, t) -> t.mutateCurrency(e)),
                arguments("tariff_id", (SnapshotMutation) (e, t) -> t.mutateTariff(e)),
                arguments("tariff_code_snapshot", (SnapshotMutation) (e, t) -> t.mutateTariffCode(e)),
                arguments("tariff_name_snapshot", (SnapshotMutation) (e, t) -> t.mutateTariffName(e)),
                arguments("duration_days_snapshot", (SnapshotMutation) (e, t) -> t.mutateDuration(e)),
                arguments("idempotence_key", (SnapshotMutation) (e, t) -> t.mutateIdempotence(e))
        );
    }

    @FunctionalInterface
    private interface SnapshotMutation {
        PreparedPaymentVerification apply(PreparedPaymentVerification expected,
                                           PaymentVerificationTransactionServiceIntegrationTest test);
    }

    @Test
    void concurrentVerificationUsesCooldownExactlyOnceAndCallsProviderOnce() throws Exception {
        long telegramId = 13001L;
        user(telegramId);
        PaymentCheckoutResult checkout = checkoutService.startCheckout(telegramId, "MONTH_1");
        clearInvocations(fakePaymentProvider);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            Future<PaymentVerificationResult> first = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return verificationFacade.verifyCurrentPayment(telegramId);
            });
            Future<PaymentVerificationResult> second = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return verificationFacade.verifyCurrentPayment(telegramId);
            });
            List<PaymentVerificationOutcome> outcomes = List.of(
                    first.get(20, TimeUnit.SECONDS).outcome(),
                    second.get(20, TimeUnit.SECONDS).outcome());

            assertThat(outcomes).containsExactlyInAnyOrder(
                    PaymentVerificationOutcome.STILL_PENDING,
                    PaymentVerificationOutcome.TOO_EARLY);
            verify(fakePaymentProvider, times(1)).getPayment(anyString());
            PaymentOrder order = reread(checkout.paymentOrderId());
            assertThat(order.getVerificationAttempts()).isEqualTo(1);
            assertThat(order.getNextVerificationAt()).isNotNull();
            assertThat(order.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(order.getActivationStatus()).isEqualTo(PaymentActivationStatus.NOT_READY);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void providerGetRunsWithoutActiveTransactionAndUnrelatedOrderCanCommit() throws Exception {
        long telegramId = 13002L;
        user(telegramId);
        PaymentCheckoutResult checkout = checkoutService.startCheckout(telegramId, "MONTH_1");
        PaymentOrder unrelated = pendingOrder(13003L, "unrelated-payment");
        CountDownLatch getStarted = new CountDownLatch(1);
        CountDownLatch releaseGet = new CountDownLatch(1);
        AtomicReference<Boolean> transactionActive = new AtomicReference<>();
        doAnswer(invocation -> {
            transactionActive.set(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive());
            getStarted.countDown();
            assertThat(releaseGet.await(10, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(fakePaymentProvider).getPayment(anyString());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<PaymentVerificationResult> verification = executor.submit(
                    () -> verificationFacade.verifyCurrentPayment(telegramId));
            assertThat(getStarted.await(10, TimeUnit.SECONDS)).isTrue();
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.executeWithoutResult(ignored -> {
                PaymentOrder locked = orderRepository.findByIdForUpdate(unrelated.getId()).orElseThrow();
                locked.markPaymentManualReviewRequired("PROVIDER_PAYMENT_NOT_FOUND", NOW);
                orderRepository.saveAndFlush(locked);
            });
            releaseGet.countDown();
            assertThat(verification.get(20, TimeUnit.SECONDS).outcome())
                    .isEqualTo(PaymentVerificationOutcome.STILL_PENDING);
            assertThat(transactionActive).hasValue(false);
            assertThat(reread(unrelated.getId()).getStatus())
                    .isEqualTo(PaymentStatus.MANUAL_REVIEW_REQUIRED);
            assertThat(reread(checkout.paymentOrderId()).getStatus()).isEqualTo(PaymentStatus.PENDING);
        } finally {
            releaseGet.countDown();
            doCallRealMethod().when(fakePaymentProvider).getPayment(anyString());
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentIdenticalSuccessAppliesOnce() throws Exception {
        PreparedPaymentVerification expected = trustedExpected(13004L);
        ProviderPayment actual = providerPayment(expected, ProviderPaymentStatus.SUCCEEDED,
                Instant.parse("2026-07-25T12:00:00.123456789Z"));
        List<PaymentVerificationResult> results = concurrentApplies(expected, actual, actual);

        assertThat(results).extracting(PaymentVerificationResult::outcome)
                .containsExactlyInAnyOrder(PaymentVerificationOutcome.SUCCEEDED,
                        PaymentVerificationOutcome.ALREADY_SUCCEEDED);
        PaymentOrder order = reread(expected.paymentOrderId());
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(order.getActivationStatus()).isEqualTo(PaymentActivationStatus.PENDING);
        assertThat(order.getPaidAt()).isEqualTo(Instant.parse("2026-07-25T12:00:00.123456Z"));
        assertThat(order.getSafeFailureCode()).isNull();
    }

    @Test
    void concurrentIdenticalCanceledAppliesOnce() throws Exception {
        PreparedPaymentVerification expected = trustedExpected(13005L);
        ProviderPayment actual = providerPayment(expected, ProviderPaymentStatus.CANCELED, null);
        List<PaymentVerificationResult> results = concurrentApplies(expected, actual, actual);

        assertThat(results).extracting(PaymentVerificationResult::outcome)
                .containsExactlyInAnyOrder(PaymentVerificationOutcome.CANCELED,
                        PaymentVerificationOutcome.ALREADY_CANCELED);
        PaymentOrder order = reread(expected.paymentOrderId());
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(order.getActivationStatus()).isEqualTo(PaymentActivationStatus.NOT_READY);
        assertThat(order.getPaidAt()).isNull();
    }

    @Test
    void concurrentSuccessAndCanceledCannotDowngradeTerminalState() throws Exception {
        PreparedPaymentVerification expected = trustedExpected(13006L);
        ProviderPayment success = providerPayment(expected, ProviderPaymentStatus.SUCCEEDED, NOW);
        ProviderPayment canceled = providerPayment(expected, ProviderPaymentStatus.CANCELED, null);
        List<PaymentVerificationResult> results = concurrentApplies(expected, success, canceled);
        PaymentOrder order = reread(expected.paymentOrderId());

        assertThat(order.getStatus()).isIn(PaymentStatus.SUCCEEDED, PaymentStatus.CANCELED);
        if (order.getStatus() == PaymentStatus.SUCCEEDED) {
            assertThat(order.getActivationStatus()).isEqualTo(PaymentActivationStatus.PENDING);
            assertThat(results).extracting(PaymentVerificationResult::outcome)
                    .contains(PaymentVerificationOutcome.SUCCEEDED);
        } else {
            assertThat(order.getActivationStatus()).isEqualTo(PaymentActivationStatus.NOT_READY);
            assertThat(order.getPaidAt()).isNull();
            assertThat(results).extracting(PaymentVerificationResult::outcome)
                    .contains(PaymentVerificationOutcome.CANCELED);
        }
    }

    @Test
    void concurrentSuccessAndManualReviewCannotDowngradeTerminalState() throws Exception {
        PreparedPaymentVerification expected = trustedExpected(13007L);
        ProviderPayment success = providerPayment(expected, ProviderPaymentStatus.SUCCEEDED, NOW);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            Future<PaymentVerificationResult> successResult = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return applyTrusted(expected, success);
            });
            Future<PaymentVerificationResult> reviewResult = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return verificationService.manualReview(expected, "PROVIDER_PAYMENT_NOT_FOUND", NOW);
            });
            successResult.get(20, TimeUnit.SECONDS);
            reviewResult.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        PaymentOrder order = reread(expected.paymentOrderId());
        assertThat(order.getStatus()).isIn(PaymentStatus.SUCCEEDED,
                PaymentStatus.MANUAL_REVIEW_REQUIRED);
        if (order.getStatus() == PaymentStatus.SUCCEEDED) {
            assertThat(order.getActivationStatus()).isEqualTo(PaymentActivationStatus.PENDING);
            assertThat(order.getSafeFailureCode()).isNull();
        } else {
            assertThat(order.getActivationStatus()).isEqualTo(PaymentActivationStatus.NOT_READY);
            assertThat(order.getPaidAt()).isNull();
            assertThat(order.getSafeFailureCode()).isEqualTo("PROVIDER_PAYMENT_NOT_FOUND");
        }
    }

    @Test
    void concurrentSuccessWithDifferentPaidAtKeepsOneAuthoritativeTimestamp() throws Exception {
        PreparedPaymentVerification expected = trustedExpected(13008L);
        Instant firstPaidAt = NOW.plusNanos(111_111_111);
        Instant secondPaidAt = NOW.plusNanos(222_222_222);
        List<PaymentVerificationResult> results = concurrentApplies(expected,
                providerPayment(expected, ProviderPaymentStatus.SUCCEEDED, firstPaidAt),
                providerPayment(expected, ProviderPaymentStatus.SUCCEEDED, secondPaidAt));
        PaymentOrder order = reread(expected.paymentOrderId());

        assertThat(results).extracting(PaymentVerificationResult::outcome)
                .contains(PaymentVerificationOutcome.SUCCEEDED);
        assertThat(order.getPaidAt()).isIn(firstPaidAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                secondPaidAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(order.getActivationStatus()).isEqualTo(PaymentActivationStatus.PENDING);
    }

    @ParameterizedTest
    @MethodSource("concurrentStaleFields")
    void concurrentStaleSnapshotIsRejected(String field, SnapshotMutation mutation) throws Exception {
        PreparedPaymentVerification expected = trustedExpected(13100L + field.hashCode());
        mutation.apply(expected, this);
        ProviderPayment actual = providerPayment(expected, ProviderPaymentStatus.SUCCEEDED, NOW);
        List<PaymentVerificationResult> results = concurrentApplies(expected, actual, actual);

        assertThat(results).extracting(PaymentVerificationResult::outcome)
                .containsOnly(PaymentVerificationOutcome.PROVIDER_RESULT_UNCERTAIN);
        PaymentOrder order = reread(expected.paymentOrderId());
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(order.getActivationStatus()).isEqualTo(PaymentActivationStatus.NOT_READY);
        assertThat(order.getPaidAt()).isNull();
    }

    static Stream<Arguments> concurrentStaleFields() {
        return Stream.of(arguments("amount", (SnapshotMutation) (e, t) -> t.mutateAmount(e)),
                arguments("providerPaymentId", (SnapshotMutation) (e, t) -> t.mutateProviderPaymentId(e)),
                arguments("idempotenceKey", (SnapshotMutation) (e, t) -> t.mutateIdempotence(e)));
    }

    private List<PaymentVerificationResult> concurrentApplies(
            PreparedPaymentVerification expected, ProviderPayment first,
            ProviderPayment second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            Future<PaymentVerificationResult> a = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return verificationService.apply(expected, first, NOW);
            });
            Future<PaymentVerificationResult> b = executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return verificationService.apply(expected, second, NOW);
            });
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertUnchanged(UUID id, PaymentOrder before, Instant updatedAt) {
        PaymentOrder after = reread(id);
        assertThat(after.getVerificationAttempts()).isEqualTo(before.getVerificationAttempts());
        assertThat(after.getNextVerificationAt()).isEqualTo(before.getNextVerificationAt());
        assertThat(after.getStatus()).isEqualTo(before.getStatus());
        assertThat(after.getPaidAt()).isEqualTo(before.getPaidAt());
        assertThat(after.getActivationStatus()).isEqualTo(before.getActivationStatus());
        assertThat(after.getSafeFailureCode()).isEqualTo(before.getSafeFailureCode());
        assertThat(after.getUpdatedAt()).isEqualTo(updatedAt);
    }

    private PaymentOrder reread(UUID id) {
        entityManager.clear();
        return orderRepository.findById(id).orElseThrow();
    }

    private PaymentOrder pendingOrder(long telegramId, String providerId) {
        return pendingOrder(user(telegramId), providerId, NOW);
    }

    private PaymentOrder pendingOrder(TelegramUser user, String providerId, Instant createdAt) {
        PaymentOrder order = baseOrder(user, createdAt);
        order.markCreating(createdAt.plusSeconds(1));
        order.markPending(providerId, "https://example.invalid/payment", createdAt,
                createdAt.plusSeconds(3600), createdAt.plusSeconds(2));
        return orderRepository.saveAndFlush(order);
    }

    private PaymentOrder creatingOrder(long telegramId) {
        return creatingOrder(user(telegramId), NOW);
    }

    private PaymentOrder creatingOrder(TelegramUser user, Instant createdAt) {
        PaymentOrder order = baseOrder(user, createdAt);
        order.markCreating(createdAt.plusSeconds(1));
        return orderRepository.saveAndFlush(order);
    }

    private PaymentOrder terminalOrder(long telegramId, PaymentStatus status) {
        return terminalOrder(user(telegramId), status, NOW);
    }

    private PaymentOrder terminalOrder(TelegramUser user, PaymentStatus status, Instant createdAt) {
        PaymentOrder order = baseOrder(user, createdAt);
        if (status == PaymentStatus.SUCCEEDED) {
            order.markCreating(createdAt.plusSeconds(1));
            order.markPending("terminal-" + status, "https://example.invalid/payment", createdAt,
                    createdAt.plusSeconds(3600), createdAt.plusSeconds(2));
            order.markSucceeded(createdAt.plusSeconds(3), createdAt.plusSeconds(4));
        } else if (status == PaymentStatus.FAILED) {
            order.markFailed("FAILED", createdAt.plusSeconds(1));
        } else if (status == PaymentStatus.EXPIRED) {
            order.markExpired(createdAt.plusSeconds(1));
        } else {
            throw new IllegalArgumentException("Unsupported terminal status");
        }
        return orderRepository.saveAndFlush(order);
    }

    private PaymentOrder baseOrder(TelegramUser user, Instant createdAt, VpnTariff tariff) {
        return PaymentOrder.create(user, tariff, PaymentProviderType.FAKE, createdAt, Duration.ofHours(1));
    }

    private PaymentOrder baseOrder(TelegramUser user, Instant createdAt) {
        VpnTariff tariff = tariffRepository.findByCodeAndActiveTrue("MONTH_1").orElseGet(() ->
                tariffRepository.saveAndFlush(VpnTariff.builder().id(UUID.randomUUID()).code("MONTH_1")
                        .name("Month").durationDays(30).price(new BigDecimal("90.00"))
                        .currency("RUB").active(true).createdAt(NOW).updatedAt(NOW).build()));
        return PaymentOrder.create(user, tariff, PaymentProviderType.FAKE, createdAt, Duration.ofHours(1));
    }

    private PreparedPaymentVerification trustedExpected(long telegramId) {
        TelegramUser user = user(telegramId);
        VpnTariff tariff = tariff(telegramId);
        PaymentOrder order = PaymentOrder.create(user, tariff, PaymentProviderType.FAKE,
                NOW, Duration.ofHours(1));
        order.markCreating(NOW.plusSeconds(1));
        order.markPending("trusted-" + telegramId, "https://example.invalid/payment",
                NOW, NOW.plusSeconds(3600), NOW.plusSeconds(2));
        orderRepository.saveAndFlush(order);
        entityManager.clear();
        return verificationService.prepare(telegramId, NOW, INTERVAL).prepared();
    }

    private PreparedPaymentVerification providerExpected(long telegramId) {
        return trustedExpected(telegramId);
    }

    private VpnTariff tariff(long telegramId) {
        return tariffRepository.saveAndFlush(VpnTariff.builder().id(UUID.randomUUID())
                .code("TX2_" + UUID.randomUUID().toString().replace("-", "")).name("Trusted tariff " + telegramId)
                .durationDays(30).price(new BigDecimal("100.00")).currency("RUB")
                .active(true).createdAt(NOW).updatedAt(NOW).build());
    }

    private ProviderPayment providerPayment(PreparedPaymentVerification expected,
                                            ProviderPaymentStatus status, Instant paidAt) {
        return providerPayment(expected, status, paidAt, NOW.minusSeconds(1));
    }

    private ProviderPayment providerPayment(PreparedPaymentVerification expected,
                                            ProviderPaymentStatus status, Instant paidAt,
                                            Instant createdAt) {
        return new ProviderPayment(expected.providerPaymentId(), status,
                status == ProviderPaymentStatus.SUCCEEDED, expected.amount(),
                expected.currency(), "fake", expected.paymentOrderId(), null,
                createdAt, paidAt);
    }

    private static ProviderPayment with(ProviderPayment payment, String providerPaymentId) {
        return new ProviderPayment(providerPaymentId, payment.status(), payment.paid(),
                payment.amount(), payment.currency(), payment.paymentMethodType(),
                payment.paymentOrderIdFromMetadata(), payment.recipientAccountId(),
                payment.createdAt(), payment.paidAt());
    }

    private static ProviderPayment with(ProviderPayment payment, BigDecimal amount, Boolean paid,
                                 String currency, UUID metadataId, String method,
                                 Instant createdAt) {
        return new ProviderPayment(payment.providerPaymentId(), payment.status(),
                paid == null ? payment.paid() : paid,
                amount == null ? payment.amount() : amount,
                currency == null ? payment.currency() : currency,
                method == null ? payment.paymentMethodType() : method,
                metadataId == null ? payment.paymentOrderIdFromMetadata() : metadataId,
                payment.recipientAccountId(),
                createdAt == null ? payment.createdAt() : createdAt,
                payment.paidAt());
    }

    private static ProviderPayment withoutMetadata(ProviderPayment payment) {
        return new ProviderPayment(payment.providerPaymentId(), payment.status(), payment.paid(),
                payment.amount(), payment.currency(), payment.paymentMethodType(), null,
                payment.recipientAccountId(), payment.createdAt(), payment.paidAt());
    }

    private PaymentVerificationResult applyTrusted(PreparedPaymentVerification expected,
                                                   ProviderPayment actual) {
        providerPaymentValidator.validate(expected, actual, NOW);
        return verificationService.apply(expected, actual, NOW);
    }

    private PaymentVerificationResult validateAndReview(PreparedPaymentVerification expected,
                                                        ProviderPayment actual) {
        try {
            providerPaymentValidator.validate(expected, actual, NOW);
            throw new AssertionError("Expected provider validation mismatch");
        } catch (ProviderPaymentValidationException exception) {
            assertThat(exception.manualReview()).isTrue();
            return verificationService.manualReview(expected, exception.safeFailureCode(), NOW);
        }
    }

    private void assertManualReview(PaymentVerificationResult result, PaymentOrder after,
                                    String expectedCode, PaymentOrder before) {
        assertThat(result.outcome()).isEqualTo(PaymentVerificationOutcome.MANUAL_REVIEW_REQUIRED);
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(after.getActivationStatus()).isEqualTo(PaymentActivationStatus.NOT_READY);
        assertThat(after.getPaidAt()).isNull();
        assertThat(after.getSafeFailureCode()).isEqualTo(expectedCode);
        assertThat(after.getProviderPaymentId()).isEqualTo(before.getProviderPaymentId());
        assertThat(after.getAmount()).isEqualByComparingTo(before.getAmount());
        assertThat(after.getCurrency()).isEqualTo(before.getCurrency());
        assertThat(after.getTariffCodeSnapshot()).isEqualTo(before.getTariffCodeSnapshot());
        assertThat(after.getTariffNameSnapshot()).isEqualTo(before.getTariffNameSnapshot());
        assertThat(after.getDurationDaysSnapshot()).isEqualTo(before.getDurationDaysSnapshot());
        assertThat(subscriptionCount()).isZero();
        assertThat(vpnAccessCount()).isZero();
    }

    private long subscriptionCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM subscriptions", Long.class);
    }

    private long vpnAccessCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM vpn_accesses", Long.class);
    }

    private PreparedPaymentVerification mutateUser(PreparedPaymentVerification expected) {
        TelegramUser other = user(12001L + Math.abs(expected.paymentOrderId().hashCode()));
        jdbcTemplate.update("UPDATE payment_orders SET user_id = ? WHERE id = ?",
                other.getId(), expected.paymentOrderId());
        entityManager.clear();
        return expected;
    }

    private PreparedPaymentVerification mutateProvider(PreparedPaymentVerification expected) {
        jdbcTemplate.update("UPDATE payment_orders SET provider = 'YOOKASSA' WHERE id = ?",
                expected.paymentOrderId());
        entityManager.clear();
        return expected;
    }

    private PreparedPaymentVerification mutateProviderPaymentId(PreparedPaymentVerification expected) {
        jdbcTemplate.update("UPDATE payment_orders SET provider_payment_id = ? WHERE id = ?",
                "changed-provider", expected.paymentOrderId());
        entityManager.clear();
        return expected;
    }

    private PreparedPaymentVerification mutateAmount(PreparedPaymentVerification expected) {
        jdbcTemplate.update("UPDATE payment_orders SET amount = 100.01 WHERE id = ?",
                expected.paymentOrderId());
        entityManager.clear();
        return expected;
    }

    private PreparedPaymentVerification mutateCurrency(PreparedPaymentVerification expected) {
        jdbcTemplate.execute("ALTER TABLE payment_orders DROP CONSTRAINT chk_payment_orders_currency_rub");
        jdbcTemplate.update("UPDATE payment_orders SET currency = 'USD' WHERE id = ?",
                expected.paymentOrderId());
        entityManager.clear();
        return expected;
    }

    private void restoreCurrencyConstraint(UUID orderId) {
        jdbcTemplate.update("UPDATE payment_orders SET currency = 'RUB' WHERE id = ?", orderId);
        jdbcTemplate.execute("ALTER TABLE payment_orders ADD CONSTRAINT chk_payment_orders_currency_rub CHECK (currency = 'RUB')");
        entityManager.clear();
    }

    private PreparedPaymentVerification mutateTariff(PreparedPaymentVerification expected) {
        VpnTariff other = tariff(12101L + Math.abs(expected.paymentOrderId().hashCode()));
        jdbcTemplate.update("UPDATE payment_orders SET tariff_id = ? WHERE id = ?",
                other.getId(), expected.paymentOrderId());
        entityManager.clear();
        return expected;
    }

    private PreparedPaymentVerification mutateTariffCode(PreparedPaymentVerification expected) {
        jdbcTemplate.update("UPDATE payment_orders SET tariff_code_snapshot = 'CHANGED_CODE' WHERE id = ?",
                expected.paymentOrderId());
        entityManager.clear();
        return expected;
    }

    private PreparedPaymentVerification mutateTariffName(PreparedPaymentVerification expected) {
        jdbcTemplate.update("UPDATE payment_orders SET tariff_name_snapshot = 'Changed tariff' WHERE id = ?",
                expected.paymentOrderId());
        entityManager.clear();
        return expected;
    }

    private PreparedPaymentVerification mutateDuration(PreparedPaymentVerification expected) {
        jdbcTemplate.update("UPDATE payment_orders SET duration_days_snapshot = 31 WHERE id = ?",
                expected.paymentOrderId());
        entityManager.clear();
        return expected;
    }

    private PreparedPaymentVerification mutateIdempotence(PreparedPaymentVerification expected) {
        jdbcTemplate.update("UPDATE payment_orders SET idempotence_key = ? WHERE id = ?",
                UUID.randomUUID(), expected.paymentOrderId());
        entityManager.clear();
        return expected;
    }

    private TelegramUser user(long telegramId) {
        return userRepository.saveAndFlush(TelegramUser.builder().id(UUID.randomUUID())
                .telegramId(telegramId).chatId(telegramId).role(UserRole.USER)
                .createdAt(NOW).updatedAt(NOW).build());
    }

    private void setProviderId(PaymentOrder order, String providerId) {
        jdbcTemplate.update("UPDATE payment_orders SET provider_payment_id = ? WHERE id = ?", providerId, order.getId());
        entityManager.clear();
    }
}
