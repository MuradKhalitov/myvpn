package ru.murad.myvpn.service;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.murad.myvpn.model.*;
import ru.murad.myvpn.repository.*;
import ru.murad.myvpn.config.VpnDeliveryProperties;
import ru.murad.myvpn.service.impl.VpnDeliveryServiceImpl;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "vpn.delivery.enabled=false")
@ActiveProfiles("test")
@Testcontainers
class VpnDeliveryPostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-07-26T10:00:00.123456789Z");
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.3-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) { r.add("spring.datasource.url", POSTGRES::getJdbcUrl); r.add("spring.datasource.username", POSTGRES::getUsername); r.add("spring.datasource.password", POSTGRES::getPassword); }
    @Autowired VpnDeliveryTransactionService transactions; @Autowired VpnDeliveryRepository deliveries;
    @Autowired TelegramUserRepository users; @Autowired VpnTariffRepository tariffs; @Autowired SubscriptionRepository subscriptions;
    @Autowired VpnAccessRepository accesses; @Autowired PaymentOrderRepository orders; @Autowired EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired VpnDeliveryProperties properties;
    private long nextTelegramId = 9001L;

    @BeforeEach void clean() { deliveries.deleteAll(); orders.deleteAll(); accesses.deleteAll(); subscriptions.deleteAll(); users.deleteAll(); tariffs.deleteAll(); }

    @Test void lastAttemptCrashIsTerminalizedAfterLeaseExpiryAndCannotBeClaimedAgain() {
        VpnDelivery delivery = seed().delivery();
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(transactions.claim(NOW.plus(Duration.ofMinutes(3L * attempt)), 10)).hasSize(1);
        }
        Instant expired = NOW.plus(Duration.ofMinutes(15));
        assertThat(transactions.markExhaustedDeliveries(expired, 10)).isOne();
        entityManager.clear();
        VpnDelivery reread = deliveries.findById(delivery.getId()).orElseThrow();
        assertThat(reread.getStatus()).isEqualTo(VpnDeliveryStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(reread.getSafeFailureCode()).isEqualTo(VpnDeliveryFailureCode.MAX_ATTEMPTS_REACHED);
        assertThat(reread.getClaimToken()).isNull(); assertThat(reread.getLeaseUntil()).isNull();
        assertThat(reread.getNextAttemptAt()).isNull(); assertThat(reread.getDeliveredAt()).isNull();
        assertThat(reread.getUpdatedAt().getNano() % 1_000).isZero();
        assertThat(transactions.claim(expired.plus(Duration.ofMinutes(3)), 10)).isEmpty();
    }

    @Test void activeLeaseAtLastAttemptIsNotTerminalizedAndExpiredLeaseBelowMaximumIsReclaimed() {
        VpnDelivery delivery = seed().delivery();
        ClaimedVpnDelivery first = transactions.claim(NOW, 10).get(0);
        assertThat(transactions.markExhaustedDeliveries(NOW.plusSeconds(1), 10)).isZero();
        ClaimedVpnDelivery reclaimed = transactions.claim(NOW.plus(Duration.ofMinutes(3)), 10).get(0);
        assertThat(reclaimed.token()).isNotEqualTo(first.token()); assertThat(reclaimed.generation()).isGreaterThan(first.generation());
        entityManager.clear();
        assertThat(deliveries.findById(delivery.getId()).orElseThrow().getStatus()).isEqualTo(VpnDeliveryStatus.PROCESSING);
    }

    @Test void automaticSnapshotContainsExactExpiryProviderAndExternalIdentityButNoConfigurationColumn() {
        VpnDelivery delivery = seed().delivery(); entityManager.clear();
        VpnDelivery reread = deliveries.findById(delivery.getId()).orElseThrow();
        assertThat(reread.getSubscriptionExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.MICROS));
        assertThat(reread.getVpnProviderName()).isEqualTo("FAKE");
        assertThat(reread.getVpnExternalAccessId()).isNotBlank();
        assertThat(entityManager.createNativeQuery("select count(*) from information_schema.columns where table_name='vpn_deliveries' and column_name='configuration_data'").getSingleResult()).isEqualTo(0L);
    }

    @Test void postgresEnforcesBothDirectionsOfDeliveryStateConstraints() {
        VpnDelivery pending = seed().delivery();
        assertThatThrownBy(() -> jdbc.update("update vpn_deliveries set claim_token=? where id=?", UUID.randomUUID(), pending.getId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update vpn_deliveries set lease_until=? where id=?", Timestamp.from(NOW), pending.getId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update vpn_deliveries set delivered_at=? where id=?", Timestamp.from(NOW), pending.getId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update vpn_deliveries set next_attempt_at=? where id=?", Timestamp.from(NOW), pending.getId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update vpn_deliveries set status='DELIVERED' where id=?", pending.getId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update vpn_deliveries set status='RETRY_REQUIRED' where id=?", pending.getId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update vpn_deliveries set attempts=-1 where id=?", pending.getId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update vpn_deliveries set generation=-1 where id=?", pending.getId())).isInstanceOf(DataIntegrityViolationException.class);
        ClaimedVpnDelivery processing = transactions.claim(NOW, 10).get(0);
        assertThatThrownBy(() -> jdbc.update("update vpn_deliveries set claim_token=null where id=?", processing.deliveryId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void automaticDeliveryRequiresSourceOrderInDomainJpaAndPostgres() {
        Seed seed = seed();
        VpnDelivery delivery = seed.delivery();
        assertThatThrownBy(() -> VpnDelivery.automatic(seed.user(), seed.subscription(), seed.access(),
                null, VpnDeliveryType.ACTIVATION_PROVISION, delivery.getConfigurationFingerprint(), NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into vpn_deliveries (id,user_id,subscription_id,vpn_access_id,source_payment_order_id,delivery_type,status,attempts,generation,claim_token,lease_until,next_attempt_at,safe_failure_code,subscription_version,subscription_expires_at,vpn_access_version,vpn_provider_name,vpn_external_access_id,configuration_fingerprint,created_at,updated_at,delivered_at,telegram_message_id,version)
                select ?,user_id,subscription_id,vpn_access_id,null,delivery_type,status,attempts,generation,claim_token,lease_until,next_attempt_at,safe_failure_code,subscription_version,subscription_expires_at,vpn_access_version,vpn_provider_name,vpn_external_access_id,configuration_fingerprint,created_at,updated_at,delivered_at,telegram_message_id,version
                from vpn_deliveries where id=?
                """, UUID.randomUUID(), delivery.getId())).isInstanceOf(DataIntegrityViolationException.class);
        VpnDelivery corrupted = VpnDelivery.automatic(seed.user(), seed.subscription(), seed.access(),
                seed.order(), VpnDeliveryType.ACTIVATION_PROVISION, delivery.getConfigurationFingerprint(), NOW);
        ReflectionTestUtils.setField(corrupted, "sourcePaymentOrder", null);
        assertThatThrownBy(() -> deliveries.saveAndFlush(corrupted)).isInstanceOf(RuntimeException.class);
    }

    @Test void postgresRejectsDuplicateSourceAndTypeButAllowsDifferentSource() {
        Seed seed = seed();
        VpnDelivery first = seed.delivery();
        VpnDelivery duplicate = VpnDelivery.automatic(seed.user(), seed.subscription(), seed.access(),
                seed.order(), VpnDeliveryType.ACTIVATION_PROVISION, first.getConfigurationFingerprint(), NOW.plusSeconds(1));
        assertThatThrownBy(() -> deliveries.saveAndFlush(duplicate)).isInstanceOf(DataIntegrityViolationException.class);
        seed.order().markExpired(NOW.plusSeconds(2));
        orders.saveAndFlush(seed.order());
        PaymentOrder differentOrder = PaymentOrder.create(seed.user(), seed.tariff(), PaymentProviderType.FAKE, NOW.plusSeconds(2), Duration.ofHours(1));
        differentOrder.attachSubscription(seed.subscription(), NOW.plusSeconds(2));
        differentOrder = orders.save(differentOrder);
        VpnDelivery differentSource = VpnDelivery.automatic(seed.user(), seed.subscription(), seed.access(),
                differentOrder, VpnDeliveryType.ACTIVATION_PROVISION, first.getConfigurationFingerprint(), NOW.plusSeconds(2));
        assertThat(deliveries.saveAndFlush(differentSource).getId()).isNotEqualTo(first.getId());
    }

    @Test void directSqlSnapshotChangesAndStatusChangesRejectGatewaySuccess() {
        assertStaleAfterSql("update subscriptions set expires_at=? where id=?", Timestamp.from(NOW.plus(Duration.ofDays(31))), "subscription expiry");
        assertStaleAfterSql("update vpn_accesses set provider_name='OTHER' where id=?", null, "provider");
        assertStaleAfterSql("update vpn_accesses set external_access_id=? where id=?", "changed-identity", "external identity");
        assertStaleAfterSql("update vpn_accesses set configuration_data=? where id=?", "changed-configuration", "configuration");
        assertStaleAfterSql("update subscriptions set status='REVOKED' where id=?", null, "subscription revoke");
        assertStaleAfterSql("update vpn_accesses set status='REVOKED' where id=?", null, "access revoke");
        assertStaleAfterSql("update subscriptions set version=version+1 where id=?", null, "subscription version");
        assertStaleAfterSql("update vpn_accesses set version=version+1 where id=?", null, "access version");
    }

    @Test void normalJpaExpiryAndRevocationChangesAlsoFenceGatewaySuccess() {
        assertStaleAfterJpaMutation(seed -> {
            seed.subscription().setActivationTarget(seed.tariff(), seed.user().getTelegramId(),
                    NOW.plus(Duration.ofDays(31)), NOW.plusSeconds(1));
            subscriptions.saveAndFlush(seed.subscription());
        });
        assertStaleAfterJpaMutation(seed -> {
            seed.subscription().revoke(NOW.plusSeconds(1));
            subscriptions.saveAndFlush(seed.subscription());
        });
        assertStaleAfterJpaMutation(seed -> {
            seed.access().revoke(NOW.plusSeconds(1));
            accesses.saveAndFlush(seed.access());
        });
    }

    @Test void ownerMismatchAndAllStaleTokenGenerationCombinationsLeaveCurrentClaimUntouched() {
        VpnDelivery delivery = seed().delivery();
        ClaimedVpnDelivery oldClaim = transactions.claim(NOW, 10).get(0);
        ClaimedVpnDelivery current = transactions.claim(NOW.plus(Duration.ofMinutes(3)), 10).get(0);
        assertThat(transactions.delivered(oldClaim, 10L, NOW.plus(Duration.ofMinutes(3)))).isFalse();
        assertThat(transactions.delivered(with(oldClaim, oldClaim.token(), current.generation()), 10L, NOW.plus(Duration.ofMinutes(3)))).isFalse();
        assertThat(transactions.delivered(with(current, current.token(), oldClaim.generation()), 10L, NOW.plus(Duration.ofMinutes(3)))).isFalse();
        entityManager.clear(); VpnDelivery reread = deliveries.findById(delivery.getId()).orElseThrow();
        assertThat(reread.getStatus()).isEqualTo(VpnDeliveryStatus.PROCESSING); assertThat(reread.getClaimToken()).isEqualTo(current.token());
        assertThat(reread.getGeneration()).isEqualTo(current.generation()); assertThat(reread.getDeliveredAt()).isNull();

        Seed ownedSeed = seed(); VpnDelivery owned = ownedSeed.delivery(); ClaimedVpnDelivery claim = transactions.claim(NOW, 10).stream().filter(c -> c.deliveryId().equals(owned.getId())).findFirst().orElseThrow();
        TelegramUser other = users.save(TelegramUser.builder().id(UUID.randomUUID()).telegramId(9901L).chatId(9901L).role(UserRole.USER).createdAt(NOW).updatedAt(NOW).build());
        jdbc.update("update subscriptions set user_id=? where id=?", other.getId(), ownedSeed.subscription().getId());
        assertThat(transactions.delivered(claim, 11L, NOW.plusSeconds(1))).isTrue(); entityManager.clear();
        assertThat(deliveries.findById(owned.getId()).orElseThrow().getDeliveredAt()).isNull();
    }

    @Test void directSqlRelationshipMismatchesBeforeTx1NeverReachGateway() {
        assertPreClaimRelationshipMismatch("vpn_access_id");
        assertPreClaimRelationshipMismatch("subscription_id");
        assertPreClaimRelationshipMismatch("user_id");
        assertPreClaimRelationshipMismatch("source_payment_order_id");
    }

    @Test void relationshipMismatchesBetweenTx1AndTx2NeverBecomeDelivered() {
        assertTx2RelationshipMismatch("vpn_access_id");
        assertTx2RelationshipMismatch("subscription_id");
        assertTx2RelationshipMismatch("user_id");
        assertTx2RelationshipMismatch("source_payment_order_id");
    }

    @Test void blankWhitespaceAndNullConfigurationsAreNeverDeliveredAfterClaim() {
        assertBlankConfigurationIsFenced("");
        assertBlankConfigurationIsFenced("   ");
        Seed seed = seed();
        ClaimedVpnDelivery claim = transactions.claim(NOW, 10).stream()
                .filter(candidate -> candidate.deliveryId().equals(seed.delivery().getId())).findFirst().orElseThrow();
        jdbc.update("update vpn_accesses set configuration_data=null where id=?", seed.access().getId());
        VpnConfigurationDeliveryGateway gateway = mock(VpnConfigurationDeliveryGateway.class);
        assertThat(worker(gateway).loadCurrentMessage(claim)).isEmpty();
        assertThat(transactions.delivered(claim, 1L, NOW.plusSeconds(1))).isTrue();
        entityManager.clear();
        VpnDelivery reread = deliveries.findById(seed.delivery().getId()).orElseThrow();
        assertThat(reread.getStatus()).isEqualTo(VpnDeliveryStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(reread.getDeliveredAt()).isNull();
        verifyNoInteractions(gateway);
    }

    private void assertPreClaimRelationshipMismatch(String column) {
        Seed a = seed(); Seed b = seed(); deliveries.deleteById(b.delivery().getId()); entityManager.clear();
        UUID replacement = switch (column) {
            case "vpn_access_id" -> b.access().getId(); case "subscription_id" -> b.subscription().getId();
            case "user_id" -> b.user().getId(); default -> b.order().getId();
        };
        jdbc.update("update vpn_deliveries set " + column + "=? where id=?", replacement, a.delivery().getId());
        VpnConfigurationDeliveryGateway gateway = mock(VpnConfigurationDeliveryGateway.class);
        assertThat(worker(gateway).processPendingDeliveries(10).claimed()).isZero();
        entityManager.clear();
        VpnDelivery reread = deliveries.findById(a.delivery().getId()).orElseThrow();
        assertThat(reread.getStatus()).isEqualTo(VpnDeliveryStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(reread.getSafeFailureCode()).isEqualTo(VpnDeliveryFailureCode.RELATIONSHIP_MISMATCH);
        assertThat(reread.getDeliveredAt()).isNull();
        verifyNoInteractions(gateway);
    }

    private void assertTx2RelationshipMismatch(String column) {
        Seed a = seed(); Seed b = seed(); deliveries.deleteById(b.delivery().getId()); entityManager.clear();
        ClaimedVpnDelivery claim = transactions.claim(NOW, 10).stream()
                .filter(candidate -> candidate.deliveryId().equals(a.delivery().getId())).findFirst().orElseThrow();
        UUID replacement = switch (column) {
            case "vpn_access_id" -> b.access().getId(); case "subscription_id" -> b.subscription().getId();
            case "user_id" -> b.user().getId(); default -> b.order().getId();
        };
        jdbc.update("update vpn_deliveries set " + column + "=? where id=?", replacement, a.delivery().getId());
        assertThat(transactions.delivered(claim, 1L, NOW.plusSeconds(1))).isTrue();
        entityManager.clear();
        VpnDelivery reread = deliveries.findById(a.delivery().getId()).orElseThrow();
        assertThat(reread.getStatus()).isEqualTo(VpnDeliveryStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(reread.getDeliveredAt()).isNull();
    }

    private void assertBlankConfigurationIsFenced(String value) {
        Seed seed = seed();
        ClaimedVpnDelivery claim = transactions.claim(NOW, 10).stream()
                .filter(candidate -> candidate.deliveryId().equals(seed.delivery().getId())).findFirst().orElseThrow();
        jdbc.update("update vpn_accesses set configuration_data=? where id=?", value, seed.access().getId());
        VpnConfigurationDeliveryGateway gateway = mock(VpnConfigurationDeliveryGateway.class);
        assertThat(worker(gateway).loadCurrentMessage(claim)).isEmpty();
        assertThat(transactions.delivered(claim, 1L, NOW.plusSeconds(1))).isTrue();
        entityManager.clear();
        VpnDelivery reread = deliveries.findById(seed.delivery().getId()).orElseThrow();
        assertThat(reread.getStatus()).isEqualTo(VpnDeliveryStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(reread.getDeliveredAt()).isNull();
        verifyNoInteractions(gateway);
    }

    private VpnDeliveryServiceImpl worker(VpnConfigurationDeliveryGateway gateway) {
        return new VpnDeliveryServiceImpl(transactions, gateway, subscriptions, accesses, orders, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void assertStaleAfterSql(String statement, Object value, String ignoredScenario) {
        Seed seed = seed(); VpnDelivery delivery = seed.delivery(); ClaimedVpnDelivery claim = transactions.claim(NOW, 10).stream().filter(c -> c.deliveryId().equals(delivery.getId())).findFirst().orElseThrow();
        if (value == null) jdbc.update(statement, statement.contains("vpn_accesses") ? seed.access().getId() : seed.subscription().getId());
        else if (statement.contains("subscriptions")) jdbc.update(statement, value, seed.subscription().getId());
        else jdbc.update(statement, value, seed.access().getId());
        assertThat(transactions.delivered(claim, 7L, NOW.plusSeconds(1))).isTrue(); entityManager.clear();
        VpnDelivery reread = deliveries.findById(delivery.getId()).orElseThrow();
        assertThat(reread.getStatus()).isEqualTo(VpnDeliveryStatus.MANUAL_REVIEW_REQUIRED); assertThat(reread.getDeliveredAt()).isNull();
    }

    private void assertStaleAfterJpaMutation(java.util.function.Consumer<Seed> mutation) {
        Seed seed = seed();
        ClaimedVpnDelivery claim = transactions.claim(NOW, 10).stream()
                .filter(candidate -> candidate.deliveryId().equals(seed.delivery().getId())).findFirst().orElseThrow();
        mutation.accept(seed);
        entityManager.clear();
        assertThat(transactions.delivered(claim, 7L, NOW.plusSeconds(2))).isTrue();
        entityManager.clear();
        VpnDelivery reread = deliveries.findById(seed.delivery().getId()).orElseThrow();
        assertThat(reread.getStatus()).isEqualTo(VpnDeliveryStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(reread.getDeliveredAt()).isNull();
    }

    private ClaimedVpnDelivery with(ClaimedVpnDelivery base, UUID token, long generation) {
        return new ClaimedVpnDelivery(base.deliveryId(), base.userId(), base.telegramId(), base.subscriptionId(), base.vpnAccessId(), base.sourcePaymentOrderId(), token,
                generation, base.subscriptionVersion(), base.vpnAccessVersion(), base.subscriptionExpiresAt(), base.vpnProviderName(),
                base.vpnExternalAccessId(), base.configurationFingerprint(), base.type(), base.expiresAt(), base.tariffName());
    }

    Seed seed() {
        long telegramId = nextTelegramId++;
        TelegramUser user = users.save(TelegramUser.builder().id(UUID.randomUUID()).telegramId(telegramId).chatId(telegramId).role(UserRole.USER).createdAt(NOW).updatedAt(NOW).build());
        VpnTariff tariff = tariffs.findAll().stream().findFirst().orElseGet(() -> tariffs.save(VpnTariff.builder().id(UUID.randomUUID()).code("T" + UUID.randomUUID().toString().substring(0, 6)).name("Test").durationDays(30).price(BigDecimal.ONE).currency("RUB").active(true).createdAt(NOW).updatedAt(NOW).build()));
        Subscription subscription = subscriptions.save(Subscription.builder().id(UUID.randomUUID()).user(user).tariff(tariff).status(SubscriptionStatus.ACTIVE).startsAt(NOW).expiresAt(NOW.plus(Duration.ofDays(30))).activatedByTelegramId(user.getTelegramId()).activatedAt(NOW).createdAt(NOW).updatedAt(NOW).build());
        VpnAccess access = accesses.save(VpnAccess.builder().id(UUID.randomUUID()).subscription(subscription).providerName("FAKE").externalAccessId(UUID.randomUUID().toString()).configurationData("private-configuration").status(VpnAccessStatus.ACTIVE).issuedAt(NOW).createdAt(NOW).updatedAt(NOW).build());
        PaymentOrder order = PaymentOrder.create(user, tariff, PaymentProviderType.FAKE, NOW, Duration.ofHours(1));
        order.attachSubscription(subscription, NOW);
        order = orders.save(order);
        return new Seed(deliveries.saveAndFlush(VpnDelivery.automatic(user, subscription, access, order, VpnDeliveryType.ACTIVATION_PROVISION, sha("private-configuration"), NOW)), user, tariff, subscription, access, order);
    }
    private record Seed(VpnDelivery delivery, TelegramUser user, VpnTariff tariff, Subscription subscription, VpnAccess access, PaymentOrder order) { }
    private String sha(String value) { try { byte[] bytes = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); StringBuilder out = new StringBuilder(); for (byte b : bytes) out.append(String.format("%02x", b)); return out.toString(); } catch (Exception e) { throw new AssertionError(e); } }
}
