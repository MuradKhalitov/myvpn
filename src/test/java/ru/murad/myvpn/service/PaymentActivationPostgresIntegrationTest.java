package ru.murad.myvpn.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.PostgreSQLContainer;
import ru.murad.myvpn.model.*;
import ru.murad.myvpn.repository.*;
import ru.murad.myvpn.client.ProvisionedVpnAccess;
import ru.murad.myvpn.exception.PaymentActivationResultMismatchException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "vpn.lifecycle.expiration-check-delay=3600000",
        "vpn.lifecycle.pending-recovery-delay=3600000",
        "payment.activation.enabled=false"
})
@ActiveProfiles("test")
@Testcontainers
class PaymentActivationPostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.3-alpine");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private PaymentActivationService activationService;
    @Autowired private PaymentActivationTransactionService activationTransactions;
    @Autowired private PaymentOrderRepository orders;
    @Autowired private SubscriptionRepository subscriptions;
    @Autowired private VpnAccessRepository accesses;
    @Autowired private VpnDeliveryRepository deliveries;
    @Autowired private TelegramUserRepository users;
    @Autowired private VpnTariffRepository tariffs;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void clean() {
        deliveries.deleteAll(); orders.deleteAll(); accesses.deleteAll(); subscriptions.deleteAll(); users.deleteAll(); tariffs.deleteAll();
    }

    @Test
    void provisionIsCommittedOnlyAfterFakeProviderAndIsIdempotent() {
        PaymentOrder order = succeededOrder(7001L);
        PaymentActivationWorkerResult first = activationService.processPendingActivations(20);
        PaymentActivationWorkerResult second = activationService.processPendingActivations(20);

        PaymentOrder saved = orders.findById(order.getId()).orElseThrow();
        assertThat(first.succeeded()).isEqualTo(1);
        assertThat(second.claimed()).isZero();
        assertThat(saved.getActivationStatus()).isEqualTo(PaymentActivationStatus.ACTIVATED);
        assertThat(saved.getActivationCompletedAt()).isNotNull();
        assertThat(subscriptions.findAll()).hasSize(1);
        assertThat(accesses.findAll()).hasSize(1);
        assertThat(subscriptions.findAll().get(0).getExpiresAt()).isEqualTo(saved.getActivationTargetExpiresAt());
    }

    @Test
    void twoWorkersClaimOneOrderAndCreateOneSubscription() throws Exception {
        succeededOrder(7002L);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<PaymentActivationWorkerResult> one = executor.submit(() -> { barrier.await(); return activationService.processPendingActivations(20); });
            Future<PaymentActivationWorkerResult> two = executor.submit(() -> { barrier.await(); return activationService.processPendingActivations(20); });
            assertThat(one.get(20, TimeUnit.SECONDS).succeeded() + two.get(20, TimeUnit.SECONDS).succeeded()).isEqualTo(1);
        } finally { executor.shutdownNow(); }
        assertThat(subscriptions.findAll()).hasSize(1);
        assertThat(accesses.findAll()).hasSize(1);
    }

    @Test
    void provisionPersistsCompletionAndClearsClaimMetadataAfterReload() {
        PaymentOrder order = succeededOrder(7003L);
        PaymentActivationWorkerResult result = activationService.processPendingActivations(20);
        PaymentOrder reread = orders.findById(order.getId()).orElseThrow();
        assertThat(result.claimed()).isEqualTo(1);
        assertThat(reread.getActivationStatus()).isEqualTo(PaymentActivationStatus.ACTIVATED);
        assertThat(reread.getActivationClaimToken()).isNull();
        assertThat(reread.getActivationLeaseUntil()).isNull();
        assertThat(reread.getNextActivationAt()).isNull();
        assertThat(reread.getSafeFailureCode()).isNull();
        assertThat(reread.getUpdatedAt()).isNotNull();
    }

    @Test
    void provisionStoresStableIdentityAndConfiguration() {
        PaymentOrder order = succeededOrder(7004L);
        activationService.processPendingActivations(20);
        VpnAccess access = accesses.findAll().get(0);
        assertThat(access.getExternalAccessId()).isEqualTo(order.getUser().getId().toString());
        assertThat(access.getConfigurationData()).isEqualTo("fake-vpn://" + access.getExternalAccessId());
    }

    @Test
    void provisionRejectsWrongIdentityAndProviderWithoutCreatingRows() {
        PaymentOrder order = succeededOrder(7030L);
        PreparedPaymentActivation prepared = activationTransactions.claimActivations(Instant.now(), 20).get(0);

        assertThatThrownBy(() -> activationTransactions.complete(prepared,
                new ProvisionedVpnAccess("OTHER", "other-client", "config", prepared.targetExpiresAt()), Instant.now()))
                .isInstanceOf(PaymentActivationResultMismatchException.class);

        assertThat(subscriptions.findAll()).isEmpty();
        assertThat(accesses.findAll()).isEmpty();
        assertThat(orders.findById(order.getId()).orElseThrow().getActivationStatus())
                .isNotEqualTo(PaymentActivationStatus.ACTIVATED);
    }

    @Test
    void provisionAcceptsOnlyStableIdentityAndProvider() {
        PaymentOrder order = succeededOrder(7031L);
        PreparedPaymentActivation prepared = activationTransactions.claimActivations(Instant.now(), 20).get(0);

        assertThat(activationTransactions.complete(prepared,
                new ProvisionedVpnAccess("FAKE", order.getUser().getId().toString(),
                        "fake-vpn://" + order.getUser().getId(), prepared.targetExpiresAt()), Instant.now()))
                .isEqualTo(PaymentActivationTransactionService.PaymentActivationOutcome.SUCCEEDED);
        assertThat(subscriptions.findAll()).hasSize(1);
        assertThat(accesses.findAll()).hasSize(1);
        assertThat(deliveries.findAll()).hasSize(1);
    }

    @Test
    void providerResultWithEqualExpiryCompletesAndCreatesSubscriptionAccessAndDelivery() {
        PaymentOrder order = succeededOrder(7036L);
        PreparedPaymentActivation prepared = activationTransactions
                .claimActivations(Instant.parse("2026-07-25T10:05:00.123456Z"), 20).get(0);

        assertThat(activationTransactions.complete(prepared,
                new ProvisionedVpnAccess("FAKE", order.getUser().getId().toString(), "config",
                        prepared.targetExpiresAt()), Instant.now()))
                .isEqualTo(PaymentActivationTransactionService.PaymentActivationOutcome.SUCCEEDED);
        assertThat(subscriptions.findAll()).hasSize(1);
        assertThat(accesses.findAll()).hasSize(1);
        assertThat(deliveries.findAll()).hasSize(1);
    }

    @Test
    void providerResultWithMillisecondExpiryAcceptsMicrosecondSnapshot() {
        PaymentOrder order = succeededOrder(7037L);
        PreparedPaymentActivation prepared = activationTransactions
                .claimActivations(Instant.parse("2026-07-25T10:05:00.123456Z"), 20).get(0);
        Instant millisecondExpiry = prepared.targetExpiresAt().truncatedTo(ChronoUnit.MILLIS);

        assertThat(prepared.targetExpiresAt()).isNotEqualTo(millisecondExpiry);
        assertThat(activationTransactions.complete(prepared,
                new ProvisionedVpnAccess("FAKE", order.getUser().getId().toString(), "config",
                        millisecondExpiry), Instant.now()))
                .isEqualTo(PaymentActivationTransactionService.PaymentActivationOutcome.SUCCEEDED);
    }

    @Test
    void providerResultExpiryDifferentBySecondIsRejected() {
        PaymentOrder order = succeededOrder(7038L);
        PreparedPaymentActivation prepared = activationTransactions.claimActivations(Instant.now(), 20).get(0);

        assertThatThrownBy(() -> activationTransactions.complete(prepared,
                new ProvisionedVpnAccess("FAKE", order.getUser().getId().toString(), "config",
                        prepared.targetExpiresAt().plusSeconds(1)), Instant.now()))
                .isInstanceOf(PaymentActivationResultMismatchException.class);
    }

    @Test
    void providerResultExternalAccessIdMismatchIsRejected() {
        PaymentOrder order = succeededOrder(7039L);
        PreparedPaymentActivation prepared = activationTransactions.claimActivations(Instant.now(), 20).get(0);

        assertThatThrownBy(() -> activationTransactions.complete(prepared,
                new ProvisionedVpnAccess("FAKE", "different-client", "config",
                        prepared.targetExpiresAt()), Instant.now()))
                .isInstanceOf(PaymentActivationResultMismatchException.class);
    }

    @Test
    void providerResultProviderNameMismatchIsRejected() {
        PaymentOrder order = succeededOrder(7040L);
        PreparedPaymentActivation prepared = activationTransactions.claimActivations(Instant.now(), 20).get(0);

        assertThatThrownBy(() -> activationTransactions.complete(prepared,
                new ProvisionedVpnAccess("OTHER", order.getUser().getId().toString(), "config",
                        prepared.targetExpiresAt()), Instant.now()))
                .isInstanceOf(PaymentActivationResultMismatchException.class);
    }

    @Test
    void revokedVpnAccessIsFencedBeforeExtend() {
        TelegramUser user = users.save(user(7032L));
        VpnTariff tariff = tariffs.save(tariff("EXTEND_7032", 30));
        Subscription subscription = activeSubscription(user, tariff, Instant.now().plusSeconds(86400), "fenced-revoked");
        PaymentOrder order = succeededOrder(user, tariff);
        PreparedPaymentActivation prepared = activationTransactions.claimActivations(Instant.now(), 20).get(0);
        VpnAccess access = accesses.findBySubscriptionId(subscription.getId()).orElseThrow();
        access.revoke(Instant.now());
        accesses.saveAndFlush(access);

        assertThat(activationTransactions.complete(prepared,
                new ProvisionedVpnAccess("FAKE", "fenced-revoked", null, prepared.targetExpiresAt()), Instant.now()))
                .isEqualTo(PaymentActivationTransactionService.PaymentActivationOutcome.STALE);
        assertThat(subscriptions.findById(subscription.getId()).orElseThrow().getExpiresAt())
                .isNotEqualTo(prepared.targetExpiresAt());
        assertThat(orders.findById(order.getId()).orElseThrow().getActivationStatus())
                .isNotEqualTo(PaymentActivationStatus.ACTIVATED);
    }

    @Test
    void deletedVpnAccessIsFencedBeforeExtend() {
        TelegramUser user = users.save(user(7033L));
        VpnTariff tariff = tariffs.save(tariff("EXTEND_7033", 30));
        Subscription subscription = activeSubscription(user, tariff, Instant.now().plusSeconds(86400), "fenced-deleted");
        PaymentOrder order = succeededOrder(user, tariff);
        PreparedPaymentActivation prepared = activationTransactions.claimActivations(Instant.now(), 20).get(0);
        VpnAccess access = accesses.findBySubscriptionId(subscription.getId()).orElseThrow();
        accesses.delete(access);
        accesses.flush();

        assertThat(activationTransactions.complete(prepared,
                new ProvisionedVpnAccess("FAKE", "fenced-deleted", null, prepared.targetExpiresAt()), Instant.now()))
                .isEqualTo(PaymentActivationTransactionService.PaymentActivationOutcome.STALE);
        assertThat(subscriptions.findById(subscription.getId()).orElseThrow().getExpiresAt())
                .isNotEqualTo(prepared.targetExpiresAt());
        assertThat(orders.findById(order.getId()).orElseThrow().getActivationStatus())
                .isNotEqualTo(PaymentActivationStatus.ACTIVATED);
    }

    @Test
    void changedVpnProviderNameIsFencedBeforeExtend() {
        TelegramUser user = users.save(user(7034L));
        VpnTariff tariff = tariffs.save(tariff("EXTEND_7034", 30));
        Subscription subscription = activeSubscription(user, tariff, Instant.now().plusSeconds(86400), "fenced-provider");
        PaymentOrder order = succeededOrder(user, tariff);
        PreparedPaymentActivation prepared = activationTransactions.claimActivations(Instant.now(), 20).get(0);
        VpnAccess access = accesses.findBySubscriptionId(subscription.getId()).orElseThrow();
        ReflectionTestUtils.setField(access, "providerName", "OTHER");
        accesses.saveAndFlush(access);

        assertThat(activationTransactions.complete(prepared,
                new ProvisionedVpnAccess("FAKE", "fenced-provider", null, prepared.targetExpiresAt()), Instant.now()))
                .isEqualTo(PaymentActivationTransactionService.PaymentActivationOutcome.STALE);
        assertThat(orders.findById(order.getId()).orElseThrow().getActivationStatus())
                .isNotEqualTo(PaymentActivationStatus.ACTIVATED);
    }

    @Test
    void changedVpnIdentityIsFencedBeforeExtend() {
        TelegramUser user = users.save(user(7035L));
        VpnTariff tariff = tariffs.save(tariff("EXTEND_7035", 30));
        Subscription subscription = activeSubscription(user, tariff, Instant.now().plusSeconds(86400), "fenced-identity");
        PaymentOrder order = succeededOrder(user, tariff);
        PreparedPaymentActivation prepared = activationTransactions.claimActivations(Instant.now(), 20).get(0);
        VpnAccess access = accesses.findBySubscriptionId(subscription.getId()).orElseThrow();
        ReflectionTestUtils.setField(access, "externalAccessId", "replacement-identity");
        accesses.saveAndFlush(access);

        assertThat(activationTransactions.complete(prepared,
                new ProvisionedVpnAccess("FAKE", "fenced-identity", null, prepared.targetExpiresAt()), Instant.now()))
                .isEqualTo(PaymentActivationTransactionService.PaymentActivationOutcome.STALE);
        assertThat(orders.findById(order.getId()).orElseThrow().getActivationStatus())
                .isNotEqualTo(PaymentActivationStatus.ACTIVATED);
    }

    @Test
    void extendUpdatesExistingSubscriptionWithoutCreatingAnother() {
        TelegramUser user = users.save(user(7005L));
        VpnTariff tariff = tariffs.save(tariff("EXTEND_7005", 30));
        Instant existingExpiry = Instant.now().plusSeconds(86400);
        Subscription subscription = subscriptions.save(Subscription.builder().id(UUID.randomUUID()).user(user).tariff(tariff)
                .status(SubscriptionStatus.ACTIVE).startsAt(NOW).expiresAt(existingExpiry)
                .activatedByTelegramId(user.getTelegramId()).activatedAt(NOW).createdAt(NOW).updatedAt(NOW).build());
        accesses.save(VpnAccess.builder().id(UUID.randomUUID()).subscription(subscription).providerName("FAKE")
                .externalAccessId("existing-client").configurationData("fake-vpn://existing-client")
                .status(VpnAccessStatus.ACTIVE).issuedAt(NOW).createdAt(NOW).updatedAt(NOW).build());
        PaymentOrder order = succeededOrder(user, tariff);

        activationService.processPendingActivations(20);
        PaymentOrder reread = orders.findById(order.getId()).orElseThrow();
        Subscription extended = subscriptions.findById(subscription.getId()).orElseThrow();
        assertThat(reread.getActivationStatus()).isEqualTo(PaymentActivationStatus.ACTIVATED);
        assertThat(extended.getExpiresAt()).isEqualTo(reread.getActivationTargetExpiresAt());
        assertThat(subscriptions.findAll()).hasSize(1);
        assertThat(accesses.findAll()).hasSize(1);
        assertThat(accesses.findAll().get(0).getExternalAccessId()).isEqualTo("existing-client");
    }

    @Test
    void extendExpiredSubscriptionUsesNowAsBase() {
        TelegramUser user = users.save(user(7006L));
        VpnTariff tariff = tariffs.save(tariff("EXTEND_7006", 30));
        Subscription subscription = subscriptions.save(Subscription.builder().id(UUID.randomUUID()).user(user).tariff(tariff)
                .status(SubscriptionStatus.ACTIVE).startsAt(NOW.minusSeconds(86400)).expiresAt(NOW.minusSeconds(60))
                .activatedByTelegramId(user.getTelegramId()).activatedAt(NOW).createdAt(NOW).updatedAt(NOW).build());
        accesses.save(VpnAccess.builder().id(UUID.randomUUID()).subscription(subscription).providerName("FAKE")
                .externalAccessId("expired-client").configurationData("fake-vpn://expired-client")
                .status(VpnAccessStatus.ACTIVE).issuedAt(NOW).createdAt(NOW).updatedAt(NOW).build());
        PaymentOrder order = succeededOrder(user, tariff);
        activationService.processPendingActivations(20);
        PaymentOrder reread = orders.findById(order.getId()).orElseThrow();
        assertThat(reread.getActivationTargetExpiresAt()).isAfter(Instant.now().plusSeconds(29L * 86400));
        assertThat(subscriptions.findById(subscription.getId()).orElseThrow().getExpiresAt())
                .isEqualTo(reread.getActivationTargetExpiresAt());
    }

    @Test
    void secondWorkerRunDoesNotExtendAgain() {
        TelegramUser user = users.save(user(7007L));
        VpnTariff tariff = tariffs.save(tariff("EXTEND_7007", 30));
        Subscription subscription = subscriptions.save(Subscription.builder().id(UUID.randomUUID()).user(user).tariff(tariff)
                .status(SubscriptionStatus.ACTIVE).startsAt(NOW).expiresAt(Instant.now().plusSeconds(86400))
                .activatedByTelegramId(user.getTelegramId()).activatedAt(NOW).createdAt(NOW).updatedAt(NOW).build());
        accesses.save(VpnAccess.builder().id(UUID.randomUUID()).subscription(subscription).providerName("FAKE")
                .externalAccessId("repeat-client").configurationData("fake-vpn://repeat-client")
                .status(VpnAccessStatus.ACTIVE).issuedAt(NOW).createdAt(NOW).updatedAt(NOW).build());
        PaymentOrder order = succeededOrder(user, tariff);
        activationService.processPendingActivations(20);
        Instant expiry = subscriptions.findById(subscription.getId()).orElseThrow().getExpiresAt();
        assertThat(activationService.processPendingActivations(20).claimed()).isZero();
        assertThat(subscriptions.findById(subscription.getId()).orElseThrow().getExpiresAt()).isEqualTo(expiry);
        assertThat(orders.findById(order.getId()).orElseThrow().getActivationStatus()).isEqualTo(PaymentActivationStatus.ACTIVATED);
    }

    @Test
    void duplicateProvisionCompletionIsAlreadyActivatedAndDoesNotDuplicateRows() {
        PaymentOrder order = succeededOrder(7008L);
        PreparedPaymentActivation prepared = activationTransactions.claimActivations(Instant.now(), 20).get(0);
        ProvisionedVpnAccess result = new ProvisionedVpnAccess("FAKE", order.getUser().getId().toString(),
                "fake-vpn://" + order.getUser().getId(), prepared.targetExpiresAt());
        assertThat(activationTransactions.complete(prepared, result, Instant.now()))
                .isEqualTo(PaymentActivationTransactionService.PaymentActivationOutcome.SUCCEEDED);
        Instant completedAt = orders.findById(order.getId()).orElseThrow().getActivationCompletedAt();
        assertThat(activationTransactions.complete(prepared, result, Instant.now()))
                .isEqualTo(PaymentActivationTransactionService.PaymentActivationOutcome.ALREADY_ACTIVATED);
        assertThat(orders.findById(order.getId()).orElseThrow().getActivationCompletedAt()).isEqualTo(completedAt);
        assertThat(subscriptions.findAll()).hasSize(1);
        assertThat(accesses.findAll()).hasSize(1);
    }

    @Test
    void duplicateExtendCompletionDoesNotIncreaseExpiryAgain() {
        TelegramUser user = users.save(user(7009L));
        VpnTariff tariff = tariffs.save(tariff("EXTEND_7009", 30));
        Subscription subscription = subscriptions.save(activeSubscription(user, tariff, Instant.now().plusSeconds(86400), "duplicate-extend"));
        PaymentOrder order = succeededOrder(user, tariff);
        PreparedPaymentActivation prepared = activationTransactions.claimActivations(Instant.now(), 20).get(0);
        Instant target = prepared.targetExpiresAt();
        ProvisionedVpnAccess result = new ProvisionedVpnAccess("FAKE", "duplicate-extend", null, target);
        assertThat(activationTransactions.complete(prepared, result, Instant.now())).isEqualTo(PaymentActivationTransactionService.PaymentActivationOutcome.SUCCEEDED);
        assertThat(activationTransactions.complete(prepared, result, Instant.now())).isEqualTo(PaymentActivationTransactionService.PaymentActivationOutcome.ALREADY_ACTIVATED);
        assertThat(subscriptions.findById(subscription.getId()).orElseThrow().getExpiresAt()).isEqualTo(target);
        assertThat(orders.findById(order.getId()).orElseThrow().getSubscription().getId()).isEqualTo(subscription.getId());
    }

    @Test
    void completionTimestampIsPersistedAtMicrosecondPrecisionAndDuplicateKeepsIt() {
        PaymentOrder order = succeededOrder(7030L);
        PreparedPaymentActivation prepared = activationTransactions.claimActivations(Instant.now(), 20).get(0);
        Instant completion = Instant.parse("2026-07-25T10:00:00.123456789Z");
        ProvisionedVpnAccess result = new ProvisionedVpnAccess("FAKE", order.getUser().getId().toString(),
                "config", prepared.targetExpiresAt());

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThat(activationTransactions.complete(prepared, result, completion))
                    .isEqualTo(PaymentActivationTransactionService.PaymentActivationOutcome.SUCCEEDED);
            entityManager.flush();
            entityManager.clear();
            PaymentOrder reread = orders.findById(order.getId()).orElseThrow();
            assertThat(reread.getActivationCompletedAt())
                    .isEqualTo(completion.truncatedTo(ChronoUnit.MICROS));
            assertThat(activationTransactions.complete(prepared, result, completion.plusSeconds(1)))
                    .isEqualTo(PaymentActivationTransactionService.PaymentActivationOutcome.ALREADY_ACTIVATED);
            entityManager.flush();
            entityManager.clear();
            assertThat(orders.findById(order.getId()).orElseThrow().getActivationCompletedAt())
                    .isEqualTo(completion.truncatedTo(ChronoUnit.MICROS));
        });
    }

    @Test
    void activeLeaseIsNotReclaimed() {
        succeededOrder(7010L);
        PreparedPaymentActivation prepared = activationTransactions.claimActivations(Instant.now(), 20).get(0);
        PaymentOrder before = orders.findById(prepared.paymentOrderId()).orElseThrow();
        PaymentActivationWorkerResult result = activationService.processPendingActivations(20);
        PaymentOrder after = orders.findById(prepared.paymentOrderId()).orElseThrow();
        assertThat(result.claimed()).isZero();
        assertThat(after.getActivationGeneration()).isEqualTo(before.getActivationGeneration());
        assertThat(after.getActivationClaimToken()).isEqualTo(before.getActivationClaimToken());
    }

    @Test
    void expiredLeaseReclaimKeepsTargetExpiryAndFencesOldClaim() {
        PaymentOrder order = succeededOrder(7011L);
        PreparedPaymentActivation first = activationTransactions.claimActivations(Instant.now().minusSeconds(180), 20).get(0);
        ProvisionedVpnAccess result = new ProvisionedVpnAccess("FAKE", order.getUser().getId().toString(),
                "fake-vpn://" + order.getUser().getId(), first.targetExpiresAt());
        PreparedPaymentActivation second = activationTransactions.claimActivations(Instant.now(), 20).get(0);
        assertThat(second.generation()).isGreaterThan(first.generation());
        assertThat(second.targetExpiresAt()).isEqualTo(first.targetExpiresAt());
        assertThat(activationTransactions.complete(first, result, Instant.now())).isEqualTo(PaymentActivationTransactionService.PaymentActivationOutcome.STALE);
        assertThat(subscriptions.findAll()).isEmpty();
    }

    @Test
    void retryReusesFixedTargetWithoutAddingDuration() {
        PaymentOrder order = succeededOrder(7012L);
        PreparedPaymentActivation prepared = activationTransactions.claimActivations(Instant.now(), 20).get(0);
        Instant target = prepared.targetExpiresAt();
        assertThat(activationTransactions.retry(prepared, "VPN_PROVIDER_TRANSIENT", Instant.now()))
                .isEqualTo(PaymentActivationTransactionService.PaymentActivationOutcome.RETRY_SCHEDULED);
        PaymentOrder reread = orders.findById(order.getId()).orElseThrow();
        assertThat(reread.getActivationTargetExpiresAt()).isEqualTo(target);
        assertThat(reread.getActivationLeaseUntil()).isNull();
        assertThat(reread.getSafeFailureCode()).isEqualTo("VPN_PROVIDER_TRANSIENT");
        assertThat(reread.getActivationCompletedAt()).isNull();
    }

    @Test
    void staleGenerationCannotApplyProviderResult() {
        PaymentOrder order = succeededOrder(7013L);
        PreparedPaymentActivation first = activationTransactions.claimActivations(Instant.now().minusSeconds(180), 20).get(0);
        PreparedPaymentActivation current = activationTransactions.claimActivations(Instant.now(), 20).get(0);
        ProvisionedVpnAccess result = new ProvisionedVpnAccess("FAKE", order.getUser().getId().toString(), "config", current.targetExpiresAt());
        assertThat(current.generation()).isGreaterThan(first.generation());
        assertThat(activationTransactions.complete(first, result, Instant.now())).isEqualTo(PaymentActivationTransactionService.PaymentActivationOutcome.STALE);
        assertThat(subscriptions.findAll()).isEmpty();
    }

    @Test
    void staleTokenCannotApplyProviderResult() {
        PaymentOrder order = succeededOrder(7027L);
        PreparedPaymentActivation first = activationTransactions.claimActivations(Instant.now().minusSeconds(180), 20).get(0);
        PreparedPaymentActivation current = activationTransactions.claimActivations(Instant.now(), 20).get(0);
        ProvisionedVpnAccess result = new ProvisionedVpnAccess("FAKE", order.getUser().getId().toString(), "config", current.targetExpiresAt());
        assertThat(current.token()).isNotEqualTo(first.token());
        assertThat(activationTransactions.complete(first, result, Instant.now())).isEqualTo(PaymentActivationTransactionService.PaymentActivationOutcome.STALE);
        assertThat(subscriptions.findAll()).isEmpty();
    }

    @Test
    void malformedProviderResultDoesNotCreateRows() {
        PaymentOrder order = succeededOrder(7014L);
        PreparedPaymentActivation prepared = activationTransactions.claimActivations(Instant.now(), 20).get(0);
        assertThatThrownBy(() -> activationTransactions.complete(prepared,
                new ProvisionedVpnAccess("FAKE", " ", "config", prepared.targetExpiresAt()), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(subscriptions.findAll()).isEmpty();
        assertThat(accesses.findAll()).isEmpty();
        assertThat(orders.findById(order.getId()).orElseThrow().getActivationStatus()).isEqualTo(PaymentActivationStatus.PROCESSING);
    }

    @Test
    void maxAttemptsMovesOrderToManualReviewWithoutProviderMutation() {
        PaymentOrder order = succeededOrder(7015L);
        org.springframework.test.util.ReflectionTestUtils.setField(order, "activationAttempts", 5);
        org.springframework.test.util.ReflectionTestUtils.setField(order, "activationCompletedAt", Instant.now());
        orders.saveAndFlush(order);
        PaymentActivationWorkerResult result = activationService.processPendingActivations(20);
        PaymentOrder reread = orders.findById(order.getId()).orElseThrow();
        assertThat(result.exhausted()).isEqualTo(1);
        assertThat(result.manualReview()).isZero();
        assertThat(reread.getActivationStatus()).isEqualTo(PaymentActivationStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(reread.getSafeFailureCode()).isEqualTo("ACTIVATION_MAX_ATTEMPTS_REACHED");
        assertThat(reread.getActivationCompletedAt()).isNull();
        assertThat(subscriptions.findAll()).isEmpty();
    }

    @Test
    void batchLimitIsRespected() {
        succeededOrder(7016L);
        succeededOrder(7017L);
        succeededOrder(7018L);
        PaymentActivationWorkerResult first = activationService.processPendingActivations(2);
        assertThat(first.claimed()).isEqualTo(2);
        assertThat(subscriptions.findAll()).hasSize(2);
        PaymentActivationWorkerResult second = activationService.processPendingActivations(2);
        assertThat(second.claimed()).isEqualTo(1);
        assertThat(subscriptions.findAll()).hasSize(3);
    }

    @Test
    void succeededPaymentWithManualReviewActivationIsNotEligible() {
        PaymentOrder order = succeededOrder(7019L);
        org.springframework.test.util.ReflectionTestUtils.setField(order, "activationStatus", PaymentActivationStatus.MANUAL_REVIEW_REQUIRED);
        orders.saveAndFlush(order);
        assertThat(activationService.processPendingActivations(20).claimed()).isZero();
        assertThat(subscriptions.findAll()).isEmpty();
    }

    @Test
    void twoWorkersClaimDifferentOrdersWithSkipLocked() throws Exception {
        succeededOrder(7020L);
        succeededOrder(7021L);
        succeededOrder(7022L);
        succeededOrder(7023L);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<PaymentActivationWorkerResult> first = executor.submit(() -> {
                barrier.await();
                return activationService.processPendingActivations(2);
            });
            Future<PaymentActivationWorkerResult> second = executor.submit(() -> {
                barrier.await();
                return activationService.processPendingActivations(2);
            });
            assertThat(first.get(20, TimeUnit.SECONDS).claimed() + second.get(20, TimeUnit.SECONDS).claimed()).isEqualTo(4);
        } finally {
            executor.shutdownNow();
        }
        assertThat(subscriptions.findAll()).hasSize(4);
        assertThat(accesses.findAll()).hasSize(4);
    }

    @Test
    void crashAfterProviderSuccessReclaimsSameTargetAndIdentity() {
        PaymentOrder order = succeededOrder(7024L);
        PreparedPaymentActivation first = activationTransactions.claimActivations(Instant.now().minusSeconds(180), 20).get(0);
        ProvisionedVpnAccess providerResult = new ProvisionedVpnAccess("FAKE", order.getUser().getId().toString(),
                "fake-vpn://" + order.getUser().getId(), first.targetExpiresAt());
        PreparedPaymentActivation reclaimed = activationTransactions.claimActivations(Instant.now(), 20).get(0);
        assertThat(reclaimed.targetExpiresAt()).isEqualTo(first.targetExpiresAt());
        assertThat(reclaimed.stableExternalClientId()).isEqualTo(first.stableExternalClientId());
        assertThat(activationTransactions.complete(reclaimed, providerResult, Instant.now()))
                .isEqualTo(PaymentActivationTransactionService.PaymentActivationOutcome.SUCCEEDED);
        assertThat(subscriptions.findAll()).hasSize(1);
        assertThat(accesses.findAll()).hasSize(1);
        assertThat(accesses.findAll().get(0).getExternalAccessId()).isEqualTo(order.getUser().getId().toString());
    }

    @Test
    void paymentPendingOrderIsNotActivationCandidate() {
        TelegramUser user = users.save(user(7025L));
        VpnTariff tariff = tariffs.save(tariff("PENDING_7025", 30));
        PaymentOrder order = orders.save(PaymentOrder.create(user, tariff, PaymentProviderType.FAKE, NOW, java.time.Duration.ofHours(1)));
        order.markCreating(NOW.plusSeconds(1));
        order.markPending("pending-7025", "https://example.invalid", NOW, null, NOW.plusSeconds(2));
        orders.saveAndFlush(order);
        assertThat(activationService.processPendingActivations(20).claimed()).isZero();
        assertThat(subscriptions.findAll()).isEmpty();
    }

    @Test
    void providerResultExpiryMustMatchSnapshotBeforeRowsAreCreated() {
        PaymentOrder order = succeededOrder(7026L);
        PreparedPaymentActivation prepared = activationTransactions.claimActivations(Instant.now(), 20).get(0);
        ProvisionedVpnAccess wrong = new ProvisionedVpnAccess("FAKE", order.getUser().getId().toString(),
                "fake-vpn://" + order.getUser().getId(), prepared.targetExpiresAt().plusSeconds(1));
        assertThatThrownBy(() -> activationTransactions.complete(prepared, wrong, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(subscriptions.findAll()).isEmpty();
        assertThat(accesses.findAll()).isEmpty();
    }

    private PaymentOrder succeededOrder(long telegramId) {
        TelegramUser user = users.save(user(telegramId));
        VpnTariff tariff = tariffs.save(tariff("ACT_" + telegramId, 30));
        return succeededOrder(user, tariff);
    }

    private PaymentOrder succeededOrder(TelegramUser user, VpnTariff tariff) {
        PaymentOrder order = orders.save(PaymentOrder.create(user, tariff, PaymentProviderType.FAKE, NOW, java.time.Duration.ofHours(1)));
        order.markCreating(NOW.plusSeconds(1));
        order.markPending("fake-" + user.getTelegramId(), "https://example.invalid/payment", NOW, null, NOW.plusSeconds(2));
        order.markSucceeded(NOW.plusSeconds(3), NOW.plusSeconds(3));
        return orders.saveAndFlush(order);
    }

    private TelegramUser user(long telegramId) {
        return TelegramUser.builder().id(UUID.randomUUID()).telegramId(telegramId).chatId(telegramId)
                .role(UserRole.USER).createdAt(NOW).updatedAt(NOW).build();
    }

    private VpnTariff tariff(String code, int durationDays) {
        return VpnTariff.builder().id(UUID.randomUUID()).code(code).name("Activation").durationDays(durationDays)
                .price(new BigDecimal("90.00")).currency("RUB").active(true).createdAt(NOW).updatedAt(NOW).build();
    }

    private Subscription activeSubscription(TelegramUser user, VpnTariff tariff, Instant expiry, String externalId) {
        Subscription subscription = subscriptions.save(Subscription.builder().id(UUID.randomUUID()).user(user).tariff(tariff)
                .status(SubscriptionStatus.ACTIVE).startsAt(NOW).expiresAt(expiry)
                .activatedByTelegramId(user.getTelegramId()).activatedAt(NOW).createdAt(NOW).updatedAt(NOW).build());
        accesses.save(VpnAccess.builder().id(UUID.randomUUID()).subscription(subscription).providerName("FAKE")
                .externalAccessId(externalId).configurationData("fake-vpn://" + externalId).status(VpnAccessStatus.ACTIVE)
                .issuedAt(NOW).createdAt(NOW).updatedAt(NOW).build());
        return subscription;
    }
}
