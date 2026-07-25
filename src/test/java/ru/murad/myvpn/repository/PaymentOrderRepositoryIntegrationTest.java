package ru.murad.myvpn.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.murad.myvpn.model.PaymentOrder;
import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.model.PaymentStatus;
import ru.murad.myvpn.model.TelegramUser;
import ru.murad.myvpn.model.UserRole;
import ru.murad.myvpn.model.VpnTariff;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "vpn.lifecycle.expiration-check-delay=3600000",
        "vpn.lifecycle.pending-recovery-delay=3600000"
})
@ActiveProfiles("test")
@Testcontainers
class PaymentOrderRepositoryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer("postgres:16.3-alpine");

    @DynamicPropertySource
    static void configurePostgresql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
    }

    @Autowired private PaymentOrderRepository paymentOrderRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private TelegramUserRepository userRepository;
    @Autowired private VpnTariffRepository tariffRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanDatabase() {
        paymentOrderRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void shouldSaveAndReadOrderWithNullableExternalReferences() {
        PaymentOrder saved = paymentOrderRepository.saveAndFlush(
                order(user(1001L), PaymentProviderType.FAKE));

        PaymentOrder found = paymentOrderRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getSubscription()).isNull();
        assertThat(found.getProviderPaymentId()).isNull();
        assertThat(found.getAmount()).isEqualByComparingTo("90.00");
        assertThat(paymentOrderRepository.findByIdempotenceKey(
                found.getIdempotenceKey())).contains(found);
    }

    @Test
    void activationGenerationMustPersistAndAdvanceOnReclaim() {
        PaymentOrder order = order(user(1031L), PaymentProviderType.FAKE);
        order.markCreating(NOW.plusSeconds(1));
        order.markSucceeded(NOW.plusSeconds(2), NOW.plusSeconds(2));
        UUID firstToken = UUID.randomUUID();
        long firstGeneration = order.claimActivation(
                firstToken, NOW.plusSeconds(60), NOW.plusSeconds(3), 5);
        UUID secondToken = UUID.randomUUID();
        long secondGeneration = order.reclaimExpiredActivation(
                NOW.plusSeconds(61), Duration.ofMinutes(2), secondToken, 5);

        PaymentOrder saved = paymentOrderRepository.saveAndFlush(order);
        PaymentOrder found = paymentOrderRepository.findById(saved.getId()).orElseThrow();

        assertThat(firstGeneration).isEqualTo(1L);
        assertThat(secondGeneration).isEqualTo(2L);
        assertThat(found.getActivationGeneration()).isEqualTo(secondGeneration);
        assertThat(found.getActivationClaimToken()).isEqualTo(secondToken);
    }

    @Test
    void idempotenceKeyMustBeUnique() {
        PaymentOrder first = paymentOrderRepository.saveAndFlush(
                order(user(1002L), PaymentProviderType.FAKE));
        PaymentOrder duplicate = order(user(1003L), PaymentProviderType.FAKE);
        ReflectionTestUtils.setField(
                duplicate, "idempotenceKey", first.getIdempotenceKey());

        assertThatThrownBy(() -> paymentOrderRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void providerPaymentIdMustBeUniqueWithinProvider() {
        PaymentOrder first = pendingOrder(user(1004L), PaymentProviderType.FAKE, "same");
        paymentOrderRepository.saveAndFlush(first);
        PaymentOrder duplicate =
                pendingOrder(user(1005L), PaymentProviderType.FAKE, "same");

        assertThatThrownBy(() -> paymentOrderRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameProviderPaymentIdMayExistForDifferentProviders() {
        paymentOrderRepository.saveAndFlush(
                pendingOrder(user(1006L), PaymentProviderType.FAKE, "same"));

        PaymentOrder yooKassa = paymentOrderRepository.saveAndFlush(
                pendingOrder(user(1007L), PaymentProviderType.YOOKASSA, "same"));

        assertThat(paymentOrderRepository.findByProviderAndProviderPaymentId(
                PaymentProviderType.YOOKASSA, "same")).contains(yooKassa);
    }

    @Test
    void multipleNullProviderPaymentIdsMustBeAllowed() {
        paymentOrderRepository.saveAndFlush(
                order(user(1008L), PaymentProviderType.FAKE));
        paymentOrderRepository.saveAndFlush(
                order(user(1009L), PaymentProviderType.FAKE));

        assertThat(paymentOrderRepository.count()).isEqualTo(2);
    }

    @Test
    void databaseMustRejectInvalidAmountCurrencyDurationAndAttempts() {
        assertDatabaseCheck("amount", "-0.01", 1010L);
        assertDatabaseCheck("currency", "'USD'", 1011L);
        assertDatabaseCheck("duration_days_snapshot", "0", 1012L);
        assertDatabaseCheck("verification_attempts", "-1", 1013L);
        assertDatabaseCheck("activation_attempts", "-1", 1014L);
        assertDatabaseCheck("activation_generation", "-1", 1032L);
    }

    @Test
    void onlyOneOpenOrderPerUserMustBeAllowed() {
        TelegramUser user = user(1015L);
        paymentOrderRepository.saveAndFlush(order(user, PaymentProviderType.FAKE));

        assertThatThrownBy(() -> paymentOrderRepository.saveAndFlush(
                order(user, PaymentProviderType.YOOKASSA)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(paymentOrderRepository.findOpenByUser(user.getId())).isPresent();
    }

    @Test
    void newOrderMustBeAllowedAfterCanceledOrExpired() {
        TelegramUser canceledUser = user(1016L);
        PaymentOrder canceled = pendingOrder(
                canceledUser, PaymentProviderType.FAKE, "canceled");
        canceled.markCanceled(NOW.plusSeconds(3));
        paymentOrderRepository.saveAndFlush(canceled);
        paymentOrderRepository.saveAndFlush(
                order(canceledUser, PaymentProviderType.YOOKASSA));

        TelegramUser expiredUser = user(1017L);
        PaymentOrder expired = order(expiredUser, PaymentProviderType.FAKE);
        expired.markExpired(NOW.plusSeconds(3));
        paymentOrderRepository.saveAndFlush(expired);
        paymentOrderRepository.saveAndFlush(
                order(expiredUser, PaymentProviderType.YOOKASSA));

        assertThat(paymentOrderRepository.count()).isEqualTo(4);
    }

    @Test
    void manualReviewMustBlockButOtherTerminalStatusesMustAllowNewOrder() {
        TelegramUser manualUser = user(1021L);
        PaymentOrder manual = pendingOrder(
                manualUser, PaymentProviderType.FAKE, "manual");
        manual.markPaymentManualReviewRequired("REVIEW", NOW.plusSeconds(3));
        paymentOrderRepository.saveAndFlush(manual);
        assertThat(paymentOrderRepository.findOpenByUser(manualUser.getId()))
                .contains(manual);
        assertThatThrownBy(() -> paymentOrderRepository.saveAndFlush(
                order(manualUser, PaymentProviderType.YOOKASSA)))
                .isInstanceOf(DataIntegrityViolationException.class);

        for (PaymentStatus status : List.of(
                PaymentStatus.SUCCEEDED,
                PaymentStatus.FAILED)) {
            TelegramUser terminalUser = user(
                    1022L + status.ordinal());
            PaymentOrder terminal = terminalOrder(terminalUser, status);
            paymentOrderRepository.saveAndFlush(terminal);
            paymentOrderRepository.saveAndFlush(
                    order(terminalUser, PaymentProviderType.YOOKASSA));
        }
    }

    @Test
    void optimisticLockMustRejectStaleEntity() {
        PaymentOrder saved = paymentOrderRepository.saveAndFlush(
                order(user(1018L), PaymentProviderType.FAKE));
        EntityManager first = entityManagerFactory.createEntityManager();
        EntityManager second = entityManagerFactory.createEntityManager();
        try {
            first.getTransaction().begin();
            second.getTransaction().begin();
            PaymentOrder current = first.find(PaymentOrder.class, saved.getId());
            PaymentOrder stale = second.find(PaymentOrder.class, saved.getId());
            current.markCreating(NOW.plusSeconds(1));
            first.getTransaction().commit();

            stale.markExpired(NOW.plusSeconds(2));
            assertThatThrownBy(() -> {
                second.merge(stale);
                second.flush();
            }).isInstanceOfAny(
                    jakarta.persistence.OptimisticLockException.class,
                    ObjectOptimisticLockingFailureException.class);
            second.getTransaction().rollback();
        } finally {
            first.close();
            second.close();
        }
        assertThat(paymentOrderRepository.findById(saved.getId()).orElseThrow()
                .getStatus()).isEqualTo(PaymentStatus.CREATING);
    }

    @Test
    void independentlyLoadedEntityAndProxyMustBeEqualInBothDirections() {
        PaymentOrder saved = paymentOrderRepository.saveAndFlush(
                order(user(1030L), PaymentProviderType.FAKE));
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        EntityManager proxyManager = entityManagerFactory.createEntityManager();
        try {
            PaymentOrder loaded = entityManager.find(PaymentOrder.class, saved.getId());
            PaymentOrder proxy = proxyManager.getReference(
                    PaymentOrder.class, saved.getId());

            assertThat(loaded).isEqualTo(proxy);
            assertThat(proxy).isEqualTo(loaded);
            assertThat(loaded).hasSameHashCodeAs(proxy);
        } finally {
            entityManager.close();
            proxyManager.close();
        }
    }

    @Test
    void findByIdForUpdateMustHoldPessimisticRowLock() throws Exception {
        PaymentOrder saved = paymentOrderRepository.saveAndFlush(
                order(user(1019L), PaymentProviderType.FAKE));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondReachedSql = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> transaction.executeWithoutResult(ignored -> {
                paymentOrderRepository.findByIdForUpdate(saved.getId()).orElseThrow();
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> {
                try {
                    transaction.executeWithoutResult(ignored -> {
                        jdbcTemplate.execute("SET LOCAL lock_timeout = '500ms'");
                        secondReachedSql.countDown();
                        paymentOrderRepository.findByIdForUpdate(
                                saved.getId()).orElseThrow();
                    });
                    return null;
                } catch (RuntimeException exception) {
                    return exception;
                }
            });

            assertThat(secondReachedSql.await(5, TimeUnit.SECONDS)).isTrue();
            RuntimeException secondFailure = second.get(5, TimeUnit.SECONDS);
            assertThat(secondFailure)
                    .isInstanceOf(org.springframework.dao
                            .PessimisticLockingFailureException.class);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentCreationMustLeaveExactlyOneOpenOrder() throws Exception {
        TelegramUser user = user(1020L);
        VpnTariff tariff = tariffRepository.findByCodeAndActiveTrue("MONTH_1")
                .orElseThrow();
        CyclicBarrier afterEmptySelect = new CyclicBarrier(2);
        AtomicInteger emptySelections = new AtomicInteger();
        List<Boolean> results = new ArrayList<>();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> raceInsertResult(
                    transaction, user, tariff, afterEmptySelect, emptySelections));
            var second = executor.submit(() -> raceInsertResult(
                    transaction, user, tariff, afterEmptySelect, emptySelections));
            results.add(first.get(10, TimeUnit.SECONDS));
            results.add(second.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertThat(emptySelections).hasValue(2);
        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM payment_orders
                WHERE user_id = ? AND status IN ('NEW', 'CREATING', 'PENDING')
                """, Long.class, user.getId())).isEqualTo(1L);
        assertThat(subscriptionRepository.count()).isZero();
    }

    private boolean raceInsertResult(
            TransactionTemplate transaction,
            TelegramUser user,
            VpnTariff tariff,
            CyclicBarrier afterEmptySelect,
            AtomicInteger emptySelections
    ) {
        try {
            transaction.executeWithoutResult(ignored -> {
                assertThat(paymentOrderRepository.findOpenByUser(user.getId()))
                        .isEmpty();
                emptySelections.incrementAndGet();
                await(afterEmptySelect);
                paymentOrderRepository.saveAndFlush(PaymentOrder.create(
                        user,
                        tariff,
                        PaymentProviderType.FAKE,
                        NOW,
                        Duration.ofHours(1)));
            });
            return true;
        } catch (DataIntegrityViolationException exception) {
            assertThat(DatabaseConstraintExtractor.extract(exception))
                    .contains("uk_payment_order_open_user");
            return false;
        }
    }

    private void assertDatabaseCheck(String column, String invalidValue, long telegramId) {
        PaymentOrder saved = paymentOrderRepository.saveAndFlush(
                order(user(telegramId), PaymentProviderType.FAKE));
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE payment_orders SET " + column + " = " + invalidValue
                        + " WHERE id = ?",
                saved.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private PaymentOrder pendingOrder(
            TelegramUser user,
            PaymentProviderType provider,
            String providerPaymentId
    ) {
        PaymentOrder order = order(user, provider);
        order.markCreating(NOW.plusSeconds(1));
        order.markPending(providerPaymentId, null, NOW, NOW.plusSeconds(2));
        return order;
    }

    private PaymentOrder terminalOrder(
            TelegramUser user,
            PaymentStatus status
    ) {
        PaymentOrder order = order(user, PaymentProviderType.FAKE);
        if (status == PaymentStatus.SUCCEEDED) {
            order.markCreating(NOW.plusSeconds(1));
            order.markSucceeded(NOW.plusSeconds(2), NOW.plusSeconds(3));
        } else if (status == PaymentStatus.FAILED) {
            order.markFailed("FAILED", NOW.plusSeconds(1));
        } else {
            throw new IllegalArgumentException("Unsupported terminal status");
        }
        return order;
    }

    private PaymentOrder order(TelegramUser user, PaymentProviderType provider) {
        VpnTariff tariff = tariffRepository.findByCodeAndActiveTrue("MONTH_1")
                .orElseThrow();
        return PaymentOrder.create(
                user, tariff, provider, NOW, Duration.ofHours(1));
    }

    private TelegramUser user(long telegramId) {
        return userRepository.saveAndFlush(TelegramUser.builder()
                .id(UUID.randomUUID())
                .telegramId(telegramId)
                .chatId(telegramId)
                .role(UserRole.USER)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build());
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test interrupted", exception);
        }
    }

    private void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("Test barrier failed", exception);
        }
    }
}
