package ru.murad.myvpn.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.murad.myvpn.client.ProvisionedVpnAccess;
import ru.murad.myvpn.client.VpnProvider;
import ru.murad.myvpn.client.VpnProvisionRequest;
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
import ru.murad.myvpn.service.impl.SubscriptionLifecycleServiceImpl;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "vpn.lifecycle.expiration-check-delay=3600000",
        "vpn.lifecycle.pending-recovery-delay=3600000"
})
@ActiveProfiles("test")
@Testcontainers
class SubscriptionConcurrencyIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer("postgres:16.3-alpine");

    @DynamicPropertySource
    static void configurePostgresql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
    }

    @Autowired private SubscriptionTransactionService transactionService;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private TelegramUserRepository userRepository;
    @Autowired private VpnTariffRepository tariffRepository;
    @Autowired private VpnAccessRepository accessRepository;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void cleanDatabase() {
        accessRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void twoRecoveryWorkersMustInvokeProviderOnlyOnce() throws Exception {
        Subscription pending = subscription(SubscriptionStatus.PENDING,
                NOW.minus(10, ChronoUnit.MINUTES));
        VpnProvider provider = mock(VpnProvider.class);
        when(provider.provision(any())).thenReturn(
                new ProvisionedVpnAccess("TEST", pending.getId().toString(), null));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        SubscriptionLifecycleService first = new SubscriptionLifecycleServiceImpl(
                accessRepository, provider, transactionService, clock);
        SubscriptionLifecycleService second = new SubscriptionLifecycleServiceImpl(
                accessRepository, provider, transactionService, clock);
        CountDownLatch start = new CountDownLatch(1);

        var executor = Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> {
                start.await();
                return first.recoverPendingSubscriptions();
            });
            var two = executor.submit(() -> {
                start.await();
                return second.recoverPendingSubscriptions();
            });
            start.countDown();
            assertThat(one.get() + two.get()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        verify(provider, times(1)).provision(any());
        assertThat(subscriptionRepository.findById(pending.getId()).orElseThrow()
                .getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void threeXUiUriMustNotBePersistedAfterNormalProvisionCompletion()
            throws Exception {
        Subscription pending = subscription(
                SubscriptionStatus.PENDING, NOW.minusSeconds(60));
        String secret = "vless://SECRET_MARKER_NORMAL";
        VpnProvider provider = mock(VpnProvider.class);
        when(provider.provision(any())).thenReturn(new ProvisionedVpnAccess(
                "3X_UI", pending.getId().toString(), secret));

        transactionService.completeProvision(
                pending.getId(),
                provider.provision(new VpnProvisionRequest(
                        pending.getId(), pending.getUser().getTelegramId(),
                        pending.getExpiresAt())),
                NOW);

        assertPersistedAccessDoesNotContainSecret(
                pending.getId(), pending.getId().toString(), secret);
    }

    @Test
    void threeXUiUriMustNotBePersistedAfterRecoveryCompletion()
            throws Exception {
        Subscription pending = subscription(
                SubscriptionStatus.RECONCILIATION_REQUIRED,
                NOW.minus(10, ChronoUnit.MINUTES));
        PendingProvisionCandidate candidate =
                claim(pending, NOW, "persistence-worker");
        String secret = "vless://SECRET_MARKER_RECOVERY";
        VpnProvider provider = mock(VpnProvider.class);
        when(provider.provision(any())).thenReturn(new ProvisionedVpnAccess(
                "3X_UI", pending.getId().toString(), secret));

        assertThat(transactionService.completeProvision(
                pending.getId(),
                candidate.claimToken(),
                provider.provision(new VpnProvisionRequest(
                        pending.getId(), pending.getUser().getTelegramId(),
                        pending.getExpiresAt())),
                NOW)).isTrue();

        assertPersistedAccessDoesNotContainSecret(
                pending.getId(), pending.getId().toString(), secret);
        assertThat(subscriptionRepository.findById(pending.getId()).orElseThrow()
                .getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void staleWorkerMustNotCompleteProvisionAfterLeaseWasReclaimed() {
        Subscription subscription = subscription(
                SubscriptionStatus.RECONCILIATION_REQUIRED, NOW.minusSeconds(60));
        PendingProvisionCandidate first = claim(subscription, NOW, "worker-a");
        PendingProvisionCandidate second = claim(
                subscription, NOW.plus(3, ChronoUnit.MINUTES), "worker-b");
        assertThat(second.claimToken()).isNotEqualTo(first.claimToken());

        boolean updated = transactionService.completeProvision(
                subscription.getId(),
                first.claimToken(),
                new ProvisionedVpnAccess(
                        "TEST", subscription.getId().toString(), null),
                NOW.plus(4, ChronoUnit.MINUTES));

        assertThat(updated).isFalse();
        Subscription current = subscriptionRepository.findById(
                subscription.getId()).orElseThrow();
        assertThat(current.getStatus())
                .isEqualTo(SubscriptionStatus.RECONCILIATION_REQUIRED);
        assertThat(current.getProvisioningClaimToken()).isEqualTo(second.claimToken());
        assertThat(current.getProvisioningLeaseUntil()).isAfter(NOW);
        assertThat(current.getProvisioningAttemptCount()).isEqualTo(2);
    }

    @Test
    void staleWorkerMustNotFailSubscriptionAfterLeaseWasReclaimed() {
        Subscription subscription = subscription(
                SubscriptionStatus.RECONCILIATION_REQUIRED, NOW.minusSeconds(60));
        PendingProvisionCandidate first = claim(subscription, NOW, "worker-a");
        PendingProvisionCandidate second = claim(
                subscription, NOW.plus(3, ChronoUnit.MINUTES), "worker-b");

        assertThat(transactionService.markProvisionFailed(
                subscription.getId(), first.claimToken(), NOW.plusSeconds(200)))
                .isFalse();

        Subscription current = subscriptionRepository.findById(
                subscription.getId()).orElseThrow();
        assertThat(current.getStatus())
                .isEqualTo(SubscriptionStatus.RECONCILIATION_REQUIRED);
        assertThat(current.getProvisioningClaimToken()).isEqualTo(second.claimToken());
        assertThat(current.getProvisioningAttemptCount()).isEqualTo(2);
    }

    @Test
    void staleWorkerMustNotReleaseNewWorkersLease() {
        Subscription subscription = subscription(
                SubscriptionStatus.RECONCILIATION_REQUIRED, NOW.minusSeconds(60));
        PendingProvisionCandidate first = claim(subscription, NOW, "worker-a");
        PendingProvisionCandidate second = claim(
                subscription, NOW.plus(3, ChronoUnit.MINUTES), "worker-b");
        Instant secondLease = subscriptionRepository.findById(
                subscription.getId()).orElseThrow().getProvisioningLeaseUntil();

        assertThat(transactionService.releaseProvisioningClaim(
                subscription.getId(), first.claimToken(), NOW.plusSeconds(200)))
                .isFalse();

        Subscription current = subscriptionRepository.findById(
                subscription.getId()).orElseThrow();
        assertThat(current.getProvisioningClaimToken()).isEqualTo(second.claimToken());
        assertThat(current.getProvisioningLeaseUntil()).isEqualTo(secondLease);
        assertThat(current.getNextProvisioningAttemptAt()).isNull();
    }

    @Test
    void lateWorkerMustNotOverwriteSuccessfulNewWorker() {
        Subscription subscription = subscription(
                SubscriptionStatus.RECONCILIATION_REQUIRED, NOW.minusSeconds(60));
        PendingProvisionCandidate first = claim(subscription, NOW, "worker-a");
        PendingProvisionCandidate second = claim(
                subscription, NOW.plus(3, ChronoUnit.MINUTES), "worker-b");
        ProvisionedVpnAccess provisioned = new ProvisionedVpnAccess(
                "TEST", subscription.getId().toString(), null);

        assertThat(transactionService.completeProvision(
                subscription.getId(), second.claimToken(), provisioned,
                NOW.plus(4, ChronoUnit.MINUTES))).isTrue();
        assertThat(transactionService.markProvisionFailed(
                subscription.getId(), first.claimToken(),
                NOW.plus(5, ChronoUnit.MINUTES))).isFalse();

        assertThat(subscriptionRepository.findById(subscription.getId()).orElseThrow()
                .getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    void simultaneousClaimsMustIssueOnlyOneTokenForOneRow() throws Exception {
        Subscription subscription = subscription(
                SubscriptionStatus.RECONCILIATION_REQUIRED, NOW.minusSeconds(60));
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> {
                start.await();
                return transactionService.claimPendingProvisioning(
                        NOW.minusSeconds(300), NOW, "worker-a");
            });
            var two = executor.submit(() -> {
                start.await();
                return transactionService.claimPendingProvisioning(
                        NOW.minusSeconds(300), NOW, "worker-b");
            });
            start.countDown();
            List<PendingProvisionCandidate> first = one.get();
            List<PendingProvisionCandidate> second = two.get();

            assertThat(first.size() + second.size()).isOne();
            PendingProvisionCandidate claimed = first.isEmpty()
                    ? second.get(0) : first.get(0);
            assertThat(claimed.claimToken()).isNotNull();
            assertThat(subscriptionRepository.findById(subscription.getId())
                    .orElseThrow().getProvisioningClaimToken())
                    .isEqualTo(claimed.claimToken());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void manualReviewMustNotBeClaimedAgainAndMustBlockSecondCurrentSubscription() {
        Subscription subscription = subscription(
                SubscriptionStatus.RECONCILIATION_REQUIRED, NOW.minusSeconds(60));
        PendingProvisionCandidate claim = claim(subscription, NOW, "worker");
        assertThat(transactionService.markManualReviewRequired(
                subscription.getId(), claim.claimToken(), NOW)).isTrue();

        assertThat(transactionService.claimPendingProvisioning(
                NOW.minus(1, ChronoUnit.DAYS),
                NOW.plus(1, ChronoUnit.DAYS),
                "another-worker")).isEmpty();
        assertThat(subscriptionRepository.findAllByStatus(
                SubscriptionStatus.MANUAL_REVIEW_REQUIRED))
                .extracting(Subscription::getId)
                .contains(subscription.getId());

        Subscription duplicate = Subscription.builder()
                .id(UUID.randomUUID())
                .user(subscription.getUser())
                .tariff(subscription.getTariff())
                .status(SubscriptionStatus.PENDING)
                .startsAt(NOW)
                .expiresAt(NOW.plus(30, ChronoUnit.DAYS))
                .activatedByTelegramId(1L)
                .activatedAt(NOW)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
        assertThatThrownBy(() -> subscriptionRepository.saveAndFlush(duplicate))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void staleWorkerMustNotRequireManualReview() {
        Subscription subscription = subscription(
                SubscriptionStatus.RECONCILIATION_REQUIRED, NOW.minusSeconds(60));
        PendingProvisionCandidate first = claim(subscription, NOW, "worker-a");
        PendingProvisionCandidate second = claim(
                subscription, NOW.plus(3, ChronoUnit.MINUTES), "worker-b");

        assertThat(transactionService.markManualReviewRequired(
                subscription.getId(), first.claimToken(), NOW.plusSeconds(200)))
                .isFalse();
        Subscription current = subscriptionRepository.findById(
                subscription.getId()).orElseThrow();
        assertThat(current.getStatus())
                .isEqualTo(SubscriptionStatus.RECONCILIATION_REQUIRED);
        assertThat(current.getProvisioningClaimToken()).isEqualTo(second.claimToken());
    }

    @Test
    void extensionAfterRevokeMustBeRejected() {
        Subscription active = activeSubscription();
        transactionService.completeRevocation(active.getId(), NOW);

        assertThatThrownBy(() -> transactionService.completeExtension(
                active.getId(), "MONTH_1", 1L,
                active.getExpiresAt().plus(30, ChronoUnit.DAYS), NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void twoParallelExtensionsMustNotLoseAnUpdate() throws Exception {
        Subscription active = activeSubscription();
        Instant expected = active.getExpiresAt().plus(30, ChronoUnit.DAYS);
        CountDownLatch start = new CountDownLatch(1);

        List<Boolean> results;
        var executor = Executors.newFixedThreadPool(2);
        try {
            var one = executor.submit(() -> extensionResult(active.getId(), expected, start));
            var two = executor.submit(() -> extensionResult(active.getId(), expected, start));
            start.countDown();
            results = List.of(one.get(), two.get());
        } finally {
            executor.shutdownNow();
        }

        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(subscriptionRepository.findById(active.getId()).orElseThrow()
                .getExpiresAt()).isEqualTo(expected);
    }

    @Test
    void reconciliationSubscriptionMustNotBeRevokedAsActive() {
        Subscription pending = subscription(
                SubscriptionStatus.RECONCILIATION_REQUIRED, NOW.minusSeconds(60));

        assertThatThrownBy(() -> transactionService.findActiveAccess(
                pending.getUser().getTelegramId()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void staleEntityVersionMustNotOverwriteNewerState() {
        Subscription active = activeSubscription();
        EntityManager first = entityManagerFactory.createEntityManager();
        EntityManager second = entityManagerFactory.createEntityManager();
        try {
            first.getTransaction().begin();
            second.getTransaction().begin();
            Subscription current = first.find(Subscription.class, active.getId());
            Subscription stale = second.find(Subscription.class, active.getId());
            current.revoke(NOW);
            first.getTransaction().commit();

            stale.markExpired(NOW);
            assertThatThrownBy(() -> {
                second.merge(stale);
                second.flush();
            }).isInstanceOf(jakarta.persistence.OptimisticLockException.class);
            second.getTransaction().rollback();
        } finally {
            first.close();
            second.close();
        }
        assertThat(subscriptionRepository.findById(active.getId()).orElseThrow()
                .getStatus()).isEqualTo(SubscriptionStatus.REVOKED);
    }

    @Test
    void migration005MustUpgradeExistingRowsAndPreserveCurrentConstraint()
            throws Exception {
        String schema = "migration_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = java.sql.DriverManager.getConnection(
                POSTGRESQL.getJdbcUrl(),
                POSTGRESQL.getUsername(),
                POSTGRESQL.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            connection.setSchema(schema);
            var database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            database.setDefaultSchemaName(schema);
            try (Liquibase liquibase = new Liquibase(
                    "db/changelog/db.changelog-master.yaml",
                    new ClassLoaderResourceAccessor(),
                    database)) {
                liquibase.update(4, new Contexts(), new LabelExpression());
                statement.execute("SET search_path TO " + schema);
                UUID tariff = UUID.fromString(resultValue(statement,
                        "SELECT id::text FROM " + schema
                                + ".vpn_tariffs WHERE code='MONTH_1'"));
                insertExistingSubscription(statement, schema, tariff,
                        SubscriptionStatus.ACTIVE, 7101L);
                insertExistingSubscription(statement, schema, tariff,
                        SubscriptionStatus.EXPIRED, 7102L);
                UUID pendingUser = insertExistingSubscription(
                        statement, schema, tariff,
                        SubscriptionStatus.PENDING, 7103L);
                connection.commit();

                liquibase.update(1, new Contexts(), new LabelExpression());
                statement.execute("SET search_path TO " + schema);
                insertExistingSubscription(statement, schema, tariff,
                        SubscriptionStatus.RECONCILIATION_REQUIRED, 7104L);
                connection.commit();
                liquibase.update(1, new Contexts(), new LabelExpression());
                statement.execute("SET search_path TO " + schema);

                var rows = statement.executeQuery(
                        "SELECT count(*), min(version), max(provisioning_attempt_count),"
                                + " count(provisioning_claim_token)"
                                + " FROM " + schema + ".subscriptions");
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(4);
                assertThat(rows.getLong(2)).isZero();
                assertThat(rows.getInt(3)).isZero();
                assertThat(rows.getInt(4)).isZero();
                UUID manualUser = insertExistingSubscription(
                        statement, schema, tariff,
                        SubscriptionStatus.MANUAL_REVIEW_REQUIRED, 7105L);
                connection.commit();
                assertThat(resultValue(statement, """
                        SELECT data_type FROM information_schema.columns
                        WHERE table_schema='%s' AND table_name='subscriptions'
                          AND column_name='provisioning_claim_token'
                        """.formatted(schema))).isEqualTo("uuid");
                assertThat(resultValue(statement, """
                        SELECT count(*)::text FROM pg_indexes
                        WHERE schemaname='%s' AND tablename='subscriptions'
                          AND indexname IN (
                            'uk_subscriptions_current_user',
                            'idx_subscriptions_provisioning_recovery')
                        """.formatted(schema))).isEqualTo("2");
                assertThatThrownBy(() -> statement.execute("""
                        INSERT INTO %s.subscriptions
                        (id,user_id,tariff_id,status,starts_at,expires_at,
                         activated_by_telegram_id,activated_at,created_at,updated_at)
                        VALUES ('00000000-0000-0000-0000-000000009998','%s','%s',
                        'ACTIVE',now(),now(),1,now(),now(),now())
                        """.formatted(schema, manualUser, tariff)))
                        .isInstanceOf(java.sql.SQLException.class);
                assertThatThrownBy(() -> statement.execute("""
                        INSERT INTO %s.subscriptions
                        (id,user_id,tariff_id,status,starts_at,expires_at,
                         activated_by_telegram_id,activated_at,created_at,updated_at)
                        VALUES ('00000000-0000-0000-0000-000000009999','%s','%s',
                        'RECONCILIATION_REQUIRED',now(),now(),1,now(),now(),now())
                        """.formatted(schema, pendingUser, tariff)))
                        .isInstanceOf(java.sql.SQLException.class);
            }
        }
    }

    @Test
    void rollback006And005MustPreserveLongProvisioningStatus() throws Exception {
        String schema = "rollback_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = java.sql.DriverManager.getConnection(
                POSTGRESQL.getJdbcUrl(),
                POSTGRESQL.getUsername(),
                POSTGRESQL.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            connection.setSchema(schema);
            var database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            database.setDefaultSchemaName(schema);
            try (Liquibase liquibase = new Liquibase(
                    "db/changelog/db.changelog-master.yaml",
                    new ClassLoaderResourceAccessor(),
                    database)) {
                liquibase.update(new Contexts(), new LabelExpression());
                statement.execute("SET search_path TO " + schema);
                UUID tariff = UUID.fromString(resultValue(statement,
                        "SELECT id::text FROM " + schema
                                + ".vpn_tariffs WHERE code='MONTH_1'"));
                insertExistingSubscription(statement, schema, tariff,
                        SubscriptionStatus.RECONCILIATION_REQUIRED, 7201L);
                UUID manualUser = insertExistingSubscription(
                        statement, schema, tariff,
                        SubscriptionStatus.MANUAL_REVIEW_REQUIRED, 7202L);
                connection.commit();

                liquibase.rollback(1, new Contexts(), new LabelExpression());
                statement.execute("SET search_path TO " + schema);
                assertThat(resultValue(statement, """
                        SELECT count(*)::text FROM information_schema.columns
                        WHERE table_schema='%s' AND table_name='payment_orders'
                          AND column_name='provider_expires_at'
                        """.formatted(schema))).isEqualTo("0");
                assertThat(resultValue(statement, """
                        SELECT count(*)::text FROM information_schema.tables
                        WHERE table_schema='%s' AND table_name='payment_orders'
                        """.formatted(schema))).isEqualTo("1");
                liquibase.update(1, new Contexts(), new LabelExpression());
                statement.execute("SET search_path TO " + schema);
                assertThat(resultValue(statement, """
                        SELECT count(*)::text FROM information_schema.columns
                        WHERE table_schema='%s' AND table_name='payment_orders'
                          AND column_name='provider_expires_at'
                        """.formatted(schema))).isEqualTo("1");
                liquibase.rollback(2, new Contexts(), new LabelExpression());
                statement.execute("SET search_path TO " + schema);
                assertThat(resultValue(statement, """
                        SELECT count(*)::text FROM information_schema.tables
                        WHERE table_schema='%s' AND table_name='payment_orders'
                        """.formatted(schema))).isEqualTo("0");
                liquibase.update(2, new Contexts(), new LabelExpression());
                statement.execute("SET search_path TO " + schema);
                assertThat(resultValue(statement, """
                        SELECT count(*)::text FROM information_schema.tables
                        WHERE table_schema='%s' AND table_name='payment_orders'
                        """.formatted(schema))).isEqualTo("1");
                assertThat(resultValue(statement, """
                        SELECT count(*)::text FROM information_schema.columns
                        WHERE table_schema='%s' AND table_name='payment_orders'
                          AND column_name='provider_expires_at'
                        """.formatted(schema))).isEqualTo("1");
                liquibase.rollback(2, new Contexts(), new LabelExpression());
                statement.execute("SET search_path TO " + schema);

                assertThatThrownBy(() -> liquibase.rollback(
                        1, new Contexts(), new LabelExpression()))
                        .isInstanceOf(liquibase.exception.LiquibaseException.class)
                        .hasStackTraceContaining(
                                "Rollback 006 requires manual resolution");
                statement.execute("SET search_path TO " + schema);
                assertThat(resultValue(statement, """
                        SELECT count(*)::text FROM information_schema.columns
                        WHERE table_schema='%s' AND table_name='subscriptions'
                          AND column_name='provisioning_claim_token'
                        """.formatted(schema))).isEqualTo("1");
                assertThat(resultValue(statement, """
                        SELECT count(*)::text FROM pg_indexes
                        WHERE schemaname='%s' AND tablename='subscriptions'
                          AND indexname='uk_subscriptions_current_user'
                        """.formatted(schema))).isEqualTo("1");
                assertThat(resultValue(statement,
                        "SELECT count(*)::text FROM " + schema
                                + ".subscriptions")).isEqualTo("2");

                statement.execute("DELETE FROM " + schema
                        + ".subscriptions WHERE user_id='" + manualUser + "'");
                statement.execute("DELETE FROM " + schema
                        + ".telegram_users WHERE id='" + manualUser + "'");
                connection.commit();
                liquibase.rollback(1, new Contexts(), new LabelExpression());
                statement.execute("SET search_path TO " + schema);
                assertThat(resultValue(statement,
                        "SELECT status FROM " + schema + ".subscriptions"))
                        .isEqualTo("RECONCILIATION_REQUIRED");
                assertThat(resultValue(statement, """
                        SELECT count(*)::text FROM information_schema.columns
                        WHERE table_schema='%s' AND table_name='subscriptions'
                          AND column_name='provisioning_claim_token'
                        """.formatted(schema))).isEqualTo("0");

                liquibase.rollback(1, new Contexts(), new LabelExpression());
                statement.execute("SET search_path TO " + schema);
                assertThat(resultValue(statement,
                        "SELECT status FROM " + schema + ".subscriptions"))
                        .isEqualTo("RECONCILIATION_REQUIRED");
                assertThat(resultValue(statement, """
                        SELECT character_maximum_length::text
                        FROM information_schema.columns
                        WHERE table_schema='%s' AND table_name='subscriptions'
                          AND column_name='status'
                        """.formatted(schema))).isEqualTo("32");
            }
        }
    }

    private void assertPersistedAccessDoesNotContainSecret(
            UUID subscriptionId,
            String expectedExternalId,
            String secret
    ) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(
                POSTGRESQL.getJdbcUrl(),
                POSTGRESQL.getUsername(),
                POSTGRESQL.getPassword());
             var statement = connection.prepareStatement("""
                     SELECT provider_name, external_access_id,
                            configuration_data, status
                     FROM vpn_accesses
                     WHERE subscription_id = ?
                     """)) {
            statement.setObject(1, subscriptionId);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("provider_name")).isEqualTo("3X_UI");
                assertThat(result.getString("external_access_id"))
                        .isEqualTo(expectedExternalId);
                assertThat(result.getString("configuration_data")).isNull();
                assertThat(result.getString("status")).isEqualTo("ACTIVE");
                for (String column : List.of(
                        "provider_name", "external_access_id", "status")) {
                    assertThat(result.getString(column))
                            .doesNotContain("vless://", secret);
                }
                assertThat(result.next()).isFalse();
            }
        }
    }

    private boolean extensionResult(
            UUID subscriptionId,
            Instant expected,
            CountDownLatch start
    ) throws InterruptedException {
        start.await();
        try {
            transactionService.completeExtension(
                    subscriptionId, "MONTH_1", 1L, expected, NOW);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private PendingProvisionCandidate claim(
            Subscription subscription,
            Instant now,
            String owner
    ) {
        return transactionService.claimPendingProvisioning(
                now.minus(5, ChronoUnit.MINUTES), now, owner)
                .stream()
                .filter(candidate -> candidate.subscriptionId().equals(subscription.getId()))
                .findFirst()
                .orElseThrow();
    }

    private Subscription activeSubscription() {
        Subscription subscription = subscription(SubscriptionStatus.ACTIVE, NOW);
        accessRepository.saveAndFlush(VpnAccess.builder()
                .id(UUID.randomUUID())
                .subscription(subscription)
                .providerName("TEST")
                .externalAccessId(subscription.getId().toString())
                .status(VpnAccessStatus.ACTIVE)
                .issuedAt(NOW)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build());
        return subscription;
    }

    private Subscription subscription(SubscriptionStatus status, Instant updatedAt) {
        TelegramUser user = userRepository.saveAndFlush(TelegramUser.builder()
                .id(UUID.randomUUID())
                .telegramId(Math.abs(UUID.randomUUID().getLeastSignificantBits()))
                .chatId(1L)
                .role(UserRole.USER)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build());
        VpnTariff tariff = tariffRepository.findByCodeAndActiveTrue("MONTH_1")
                .orElseThrow();
        return subscriptionRepository.saveAndFlush(Subscription.builder()
                .id(UUID.randomUUID())
                .user(user)
                .tariff(tariff)
                .status(status)
                .startsAt(NOW)
                .expiresAt(NOW.plus(30, ChronoUnit.DAYS))
                .activatedByTelegramId(1L)
                .activatedAt(NOW)
                .createdAt(updatedAt)
                .updatedAt(updatedAt)
                .build());
    }

    private String resultValue(java.sql.Statement statement, String sql)
            throws java.sql.SQLException {
        var result = statement.executeQuery(sql);
        result.next();
        return result.getString(1);
    }

    private UUID insertExistingSubscription(
            java.sql.Statement statement,
            String schema,
            UUID tariff,
            SubscriptionStatus status,
            long telegramId
    ) throws java.sql.SQLException {
        UUID user = UUID.randomUUID();
        statement.execute("""
                INSERT INTO %s.telegram_users
                (id,telegram_id,chat_id,role,created_at,updated_at)
                VALUES ('%s',%d,%d,'USER',now(),now())
                """.formatted(schema, user, telegramId, telegramId));
        statement.execute("""
                INSERT INTO %s.subscriptions
                (id,user_id,tariff_id,status,starts_at,expires_at,
                 activated_by_telegram_id,activated_at,created_at,updated_at)
                VALUES ('%s','%s','%s','%s',now(),now(),1,now(),now(),now())
                """.formatted(schema, UUID.randomUUID(), user, tariff, status));
        return user;
    }
}
