package ru.murad.myvpn.service;

import org.junit.jupiter.api.*;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.murad.myvpn.client.ProvisionedVpnAccess;
import ru.murad.myvpn.model.*;
import ru.murad.myvpn.repository.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"payment.activation.enabled=false", "vpn.delivery.enabled=false"}) @ActiveProfiles("test") @Testcontainers
class PaymentActivationDeliveryPostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-07-26T10:00:00.123456789Z");
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.3-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) { r.add("spring.datasource.url", POSTGRES::getJdbcUrl); r.add("spring.datasource.username", POSTGRES::getUsername); r.add("spring.datasource.password", POSTGRES::getPassword); }
    @Autowired PaymentActivationTransactionService activation; @Autowired PaymentOrderRepository orders; @Autowired VpnDeliveryRepository deliveries;
    @Autowired VpnDeliveryTransactionService deliveryTransactions;
    @Autowired TelegramUserRepository users; @Autowired AccountRepository accounts; @Autowired VpnTariffRepository tariffs; @Autowired SubscriptionRepository subscriptions; @Autowired VpnAccessRepository accesses; @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @BeforeEach void clean() { deliveries.deleteAll(); orders.deleteAll(); accesses.deleteAll(); subscriptions.deleteAll(); users.deleteAll(); accounts.deleteAll(); tariffs.deleteAll(); }
    @Test void provisionCreatesOneDurableDeliveryWithoutConfigurationCopyAndMicros() {
        PaymentOrder order = succeededOrder(); PreparedPaymentActivation claim = activation.claimActivations(NOW, 10).get(0);
        assertThat(activation.complete(claim, result(claim, "fake-vpn://top-secret"), NOW)).isEqualTo(PaymentActivationTransactionService.PaymentActivationOutcome.SUCCEEDED);
        VpnDelivery delivery = deliveries.findAll().get(0); PaymentOrder reread = orders.findById(order.getId()).orElseThrow();
        assertThat(reread.getActivationStatus()).isEqualTo(PaymentActivationStatus.ACTIVATED); assertThat(delivery.getStatus()).isEqualTo(VpnDeliveryStatus.PENDING);
        assertThat(delivery.getSourcePaymentOrder().getId()).isEqualTo(order.getId()); assertThat(delivery.getSubscription().getId()).isEqualTo(reread.getSubscription().getId());
        assertThat(accesses.findById(delivery.getVpnAccess().getId()).orElseThrow().getConfigurationData()).isEqualTo("fake-vpn://top-secret"); assertThat(delivery.getConfigurationFingerprint()).isEqualTo(sha("fake-vpn://top-secret"));
        assertThat(delivery.getCreatedAt().getNano() % 1_000).isZero(); assertThat(delivery.getUpdatedAt().getNano() % 1_000).isZero();
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_name='vpn_deliveries' and column_name='configuration_data'", Integer.class)).isZero();
    }
    @Test void duplicateAndConcurrentCompletionCreateOneDeliveryOnly() throws Exception {
        PaymentOrder order = succeededOrder(); PreparedPaymentActivation claim = activation.claimActivations(NOW, 10).get(0); ProvisionedVpnAccess result = result(claim, "fake-vpn://secret");
        ExecutorService executor = Executors.newFixedThreadPool(2); CountDownLatch ready = new CountDownLatch(2); CountDownLatch go = new CountDownLatch(1);
        try { List<Future<PaymentActivationTransactionService.PaymentActivationOutcome>> futures = List.of(executor.submit(() -> { ready.countDown(); go.await(); return activation.complete(claim, result, NOW); }), executor.submit(() -> { ready.countDown(); go.await(); return activation.complete(claim, result, NOW); }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); go.countDown();
            List<PaymentActivationTransactionService.PaymentActivationOutcome> outcomes = List.of(futures.get(0).get(10, TimeUnit.SECONDS), futures.get(1).get(10, TimeUnit.SECONDS));
            assertThat(outcomes).contains(PaymentActivationTransactionService.PaymentActivationOutcome.SUCCEEDED).allMatch(o -> o == PaymentActivationTransactionService.PaymentActivationOutcome.SUCCEEDED || o == PaymentActivationTransactionService.PaymentActivationOutcome.ALREADY_ACTIVATED);
        } finally { executor.shutdownNow(); }
        assertThat(deliveries.count()).isEqualTo(1); assertThat(subscriptions.count()).isEqualTo(1); assertThat(accesses.count()).isEqualTo(1);
    }
    @Test void threeOrdersProvisionAndExtendReuseIdentityAndEachCreateDelivery() {
        PaymentOrder first = succeededOrder(); PreparedPaymentActivation firstClaim = activation.claimActivations(NOW, 10).get(0); activation.complete(firstClaim, result(firstClaim, "fake-vpn://secret"), NOW);
        Instant initialExpiry = subscriptions.findAll().get(0).getExpiresAt(); String identity = accesses.findAll().get(0).getExternalAccessId();
        for (int i = 0; i < 2; i++) { PaymentOrder next = succeededOrder(); PreparedPaymentActivation extend = activation.claimActivations(NOW.plusSeconds(i + 1), 10).stream().filter(p -> p.paymentOrderId().equals(next.getId())).findFirst().orElseThrow();
            activation.complete(extend, new ProvisionedVpnAccess("FAKE", identity, null, extend.targetExpiresAt()), NOW.plusSeconds(i + 1)); }
        assertThat(subscriptions.count()).isOne(); assertThat(accesses.count()).isOne(); assertThat(accesses.findAll().get(0).getExternalAccessId()).isEqualTo(identity); assertThat(deliveries.count()).isEqualTo(3);
        assertThat(subscriptions.findAll().get(0).getExpiresAt()).isAfter(initialExpiry); assertThat(deliveries.findAll()).extracting(d -> d.getSourcePaymentOrder().getId()).doesNotHaveDuplicates();
    }
    @Test void provisionAndExtendSnapshotsUseFlushedVersionsAndCanCompleteDelivery() {
        PaymentOrder provision = succeededOrder();
        PreparedPaymentActivation provisionClaim = activation.claimActivations(NOW, 10).get(0);
        assertThat(activation.complete(provisionClaim, result(provisionClaim, "fake-vpn://test"), NOW))
                .isEqualTo(PaymentActivationTransactionService.PaymentActivationOutcome.SUCCEEDED);
        assertSnapshotMatchesPersistedVersionsAndCompletes(provision.getId(), NOW.plusSeconds(1));

        PaymentOrder extension = succeededOrder();
        PreparedPaymentActivation extensionClaim = activation.claimActivations(NOW.plusSeconds(2), 10).stream()
                .filter(candidate -> candidate.paymentOrderId().equals(extension.getId())).findFirst().orElseThrow();
        assertThat(activation.complete(extensionClaim,
                new ProvisionedVpnAccess("FAKE", extensionClaim.stableExternalClientId(), null,
                        extensionClaim.targetExpiresAt()), NOW.plusSeconds(2)))
                .isEqualTo(PaymentActivationTransactionService.PaymentActivationOutcome.SUCCEEDED);
        assertSnapshotMatchesPersistedVersionsAndCompletes(extension.getId(), NOW.plusSeconds(3));
    }
    @Test void foreignAccessInForgedExtendCompletionRollsBackActivationAndDelivery() {
        PaymentOrder first = succeededOrder(); PreparedPaymentActivation firstClaim = activation.claimActivations(NOW, 10).get(0);
        activation.complete(firstClaim, result(firstClaim, "fake-vpn://secret"), NOW);
        Subscription subscriptionA = subscriptions.findAll().get(0); Instant originalExpiry = subscriptionA.getExpiresAt();
        PaymentOrder extension = succeededOrder(); PreparedPaymentActivation claim = activation.claimActivations(NOW.plusSeconds(1), 10)
                .stream().filter(candidate -> candidate.paymentOrderId().equals(extension.getId())).findFirst().orElseThrow();
        TelegramUser userB = ru.murad.myvpn.support.AccountTestData.saveTelegramUser(accounts, users, TelegramUser.builder().id(UUID.randomUUID()).telegramId(8002L).chatId(8002L).role(UserRole.USER).createdAt(NOW).updatedAt(NOW).build());
        VpnTariff tariff = tariffs.findAll().get(0);
        Subscription subscriptionB = subscriptions.save(Subscription.builder().id(UUID.randomUUID()).user(userB).tariff(tariff).status(SubscriptionStatus.ACTIVE)
                .startsAt(NOW).expiresAt(NOW.plus(Duration.ofDays(30))).activatedByTelegramId(userB.getTelegramId()).activatedAt(NOW).createdAt(NOW).updatedAt(NOW).build());
        VpnAccess accessB = accesses.save(VpnAccess.builder().id(UUID.randomUUID()).subscription(subscriptionB).providerName("FAKE")
                .externalAccessId(UUID.randomUUID().toString()).configurationData("foreign-configuration").status(VpnAccessStatus.ACTIVE).issuedAt(NOW).createdAt(NOW).updatedAt(NOW).build());
        PreparedPaymentActivation forged = new PreparedPaymentActivation(claim.paymentOrderId(), claim.userId(), claim.provider(), claim.action(),
                claim.generation(), claim.token(), claim.durationDays(), claim.targetExpiresAt(), claim.existingSubscriptionId(), accessB.getId(),
                accessB.getExternalAccessId(), claim.existingSubscriptionVersion(), claim.existingSubscriptionExpiresAt(), claim.paymentStatus(),
                claim.activationStatus(), claim.tariffId(), claim.tariffCodeSnapshot(), claim.tariffNameSnapshot(), "FAKE", accessB.getVersion(),
                accessB.getStatus(), accessB.getProviderName());

        assertThatThrownBy(() -> activation.complete(forged,
                new ProvisionedVpnAccess("FAKE", accessB.getExternalAccessId(), null, claim.targetExpiresAt()), NOW.plusSeconds(1)))
                .isInstanceOf(ru.murad.myvpn.exception.PaymentOrderValidationException.class);
        entityManagerClear();
        assertThat(orders.findById(extension.getId()).orElseThrow().getActivationStatus()).isEqualTo(PaymentActivationStatus.PROCESSING);
        assertThat(subscriptions.findById(subscriptionA.getId()).orElseThrow().getExpiresAt()).isEqualTo(originalExpiry);
        assertThat(deliveries.count()).isEqualTo(1);
    }
    private void entityManagerClear() { entityManager.clear(); }
    private void assertSnapshotMatchesPersistedVersionsAndCompletes(UUID orderId, Instant claimedAt) {
        entityManager.clear();
        VpnDelivery delivery = deliveries.findAll().stream()
                .filter(candidate -> candidate.getSourcePaymentOrder().getId().equals(orderId)).findFirst().orElseThrow();
        Subscription subscription = subscriptions.findById(delivery.getSubscription().getId()).orElseThrow();
        VpnAccess access = accesses.findById(delivery.getVpnAccess().getId()).orElseThrow();
        assertThat(delivery.getSubscriptionVersion()).isEqualTo(subscription.getVersion());
        assertThat(delivery.getVpnAccessVersion()).isEqualTo(access.getVersion());
        ClaimedVpnDelivery claim = deliveryTransactions.claim(claimedAt, 10).stream()
                .filter(candidate -> candidate.deliveryId().equals(delivery.getId())).findFirst().orElseThrow();
        assertThat(deliveryTransactions.delivered(claim, 1L, claimedAt.plusMillis(1))).isTrue();
        entityManager.clear();
        VpnDelivery reread = deliveries.findById(delivery.getId()).orElseThrow();
        assertThat(reread.getStatus()).isEqualTo(VpnDeliveryStatus.DELIVERED);
        assertThat(reread.getDeliveredAt()).isNotNull();
    }
    private PaymentOrder succeededOrder() { TelegramUser user = users.findAll().stream().findFirst().orElseGet(() -> ru.murad.myvpn.support.AccountTestData.saveTelegramUser(accounts, users, TelegramUser.builder().id(UUID.randomUUID()).telegramId(8001L).chatId(8001L).role(UserRole.USER).createdAt(NOW).updatedAt(NOW).build())); VpnTariff tariff = tariffs.findAll().stream().findFirst().orElseGet(() -> tariffs.save(VpnTariff.builder().id(UUID.randomUUID()).code("MONTH").name("Month").durationDays(30).price(new BigDecimal("90.00")).currency("RUB").active(true).createdAt(NOW).updatedAt(NOW).build())); PaymentOrder order = PaymentOrder.create(user, tariff, PaymentProviderType.FAKE, NOW, Duration.ofHours(1)); order.markCreating(NOW); order.markPending(UUID.randomUUID().toString(), "https://example.invalid", NOW, null, NOW); order.markSucceeded(NOW, NOW); return orders.saveAndFlush(order); }
    private ProvisionedVpnAccess result(PreparedPaymentActivation claim, String config) { return new ProvisionedVpnAccess("FAKE", claim.stableExternalClientId(), config, claim.targetExpiresAt()); }
    private String sha(String value) { try { byte[] bytes = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format("%02x", b)); return out.toString(); } catch (Exception e) { throw new AssertionError(e); } }
}
