package ru.murad.myvpn.model;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class VpnDeliveryDomainTest {
    private static final Instant NOW = Instant.parse("2026-07-26T10:00:00.123456789Z");
    @Test void claimRetryReclaimAndFencingAreSafeAndSecretFree() {
        VpnDelivery delivery = delivery(); UUID first = UUID.randomUUID();
        long generation = delivery.claim(first, NOW, Duration.ofMinutes(1), 5);
        assertThat(delivery.getStatus()).isEqualTo(VpnDeliveryStatus.PROCESSING);
        assertThat(delivery.getAttempts()).isOne();
        assertThat(delivery.getLeaseUntil().getNano()).isEqualTo(123456000);
        assertThatThrownBy(() -> delivery.delivered(UUID.randomUUID(), generation, 1L, NOW)).isInstanceOf(IllegalStateException.class);
        delivery.retry(first, generation, VpnDeliveryFailureCode.TELEGRAM_UNAVAILABLE, NOW, NOW.plusSeconds(5));
        assertThat(delivery.getStatus()).isEqualTo(VpnDeliveryStatus.RETRY_REQUIRED);
        UUID second = UUID.randomUUID(); long next = delivery.claim(second, NOW.plusSeconds(5), Duration.ofMinutes(1), 5);
        delivery.delivered(second, next, 9L, NOW.plusSeconds(6));
        assertThat(delivery.getStatus()).isEqualTo(VpnDeliveryStatus.DELIVERED);
        assertThat(delivery.getDeliveredAt()).isEqualTo(NOW.plusSeconds(6).truncatedTo(java.time.temporal.ChronoUnit.MICROS));
        assertThat(delivery.toString()).doesNotContain("fake-vpn", "configuration", UUID.class.getName());
    }
    @Test void constructorRejectsNonWhitelistedFingerprint() {
        TelegramUser user = user(); Subscription subscription = subscription(user); VpnAccess access = access(subscription);
        PaymentOrder order = order(user, subscription.getTariff());
        assertThatThrownBy(() -> VpnDelivery.automatic(user, subscription, access, order, VpnDeliveryType.ACTIVATION_PROVISION, "secret", NOW)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void automaticDeliveryRejectsMissingSourcePaymentOrder() {
        TelegramUser user = user(); Subscription subscription = subscription(user);
        assertThatThrownBy(() -> VpnDelivery.automatic(user, subscription, access(subscription), null,
                VpnDeliveryType.ACTIVATION_PROVISION, "a".repeat(64), NOW)).isInstanceOf(NullPointerException.class);
    }
    @Test void automaticDeliveryRejectsEveryCrossOwnerRelationship() {
        TelegramUser userA = user(10L); TelegramUser userB = user(20L);
        Subscription subscriptionA = subscription(userA); Subscription subscriptionB = subscription(userB);
        VpnAccess accessA = access(subscriptionA); VpnAccess accessB = access(subscriptionB);
        PaymentOrder orderA = order(userA, subscriptionA.getTariff()); orderA.attachSubscription(subscriptionA, NOW);
        PaymentOrder orderB = order(userB, subscriptionB.getTariff()); orderB.attachSubscription(subscriptionB, NOW);
        assertThatThrownBy(() -> VpnDelivery.automatic(userA, subscriptionB, accessB, orderB,
                VpnDeliveryType.ACTIVATION_PROVISION, "a".repeat(64), NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VpnDelivery.automatic(userA, subscriptionA, accessB, orderA,
                VpnDeliveryType.ACTIVATION_PROVISION, "a".repeat(64), NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VpnDelivery.automatic(userA, subscriptionA, accessA, orderB,
                VpnDeliveryType.ACTIVATION_PROVISION, "a".repeat(64), NOW)).isInstanceOf(IllegalArgumentException.class);
        PaymentOrder wrongSubscriptionOrder = order(userA, subscriptionA.getTariff()); wrongSubscriptionOrder.attachSubscription(subscriptionB, NOW);
        assertThatThrownBy(() -> VpnDelivery.automatic(userA, subscriptionA, accessA, wrongSubscriptionOrder,
                VpnDeliveryType.ACTIVATION_PROVISION, "a".repeat(64), NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThat(VpnDelivery.automatic(userA, subscriptionA, accessA, orderA,
                VpnDeliveryType.ACTIVATION_PROVISION, "a".repeat(64), NOW).getStatus()).isEqualTo(VpnDeliveryStatus.PENDING);
    }
    @Test void expiredLastClaimIsTerminalizedAndClearsProcessingState() {
        VpnDelivery delivery = delivery(); UUID token = UUID.randomUUID();
        delivery.claim(token, NOW, Duration.ofSeconds(1), 1);
        delivery.markExhausted(NOW.plusSeconds(1));
        assertThat(delivery.getStatus()).isEqualTo(VpnDeliveryStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(delivery.getSafeFailureCode()).isEqualTo(VpnDeliveryFailureCode.MAX_ATTEMPTS_REACHED);
        assertThat(delivery.getClaimToken()).isNull(); assertThat(delivery.getLeaseUntil()).isNull();
        assertThat(delivery.getNextAttemptAt()).isNull(); assertThat(delivery.getDeliveredAt()).isNull();
        assertThat(delivery.getUpdatedAt().getNano() % 1_000).isZero();
    }
    @Test void activeLastClaimCannotBeTerminalizedBeforeLeaseExpiry() {
        VpnDelivery delivery = delivery(); delivery.claim(UUID.randomUUID(), NOW, Duration.ofMinutes(1), 1);
        assertThatThrownBy(() -> delivery.markExhausted(NOW.plusSeconds(1))).isInstanceOf(IllegalStateException.class);
    }
    private VpnDelivery delivery() { TelegramUser user = user(); Subscription subscription = subscription(user); PaymentOrder order = order(user, subscription.getTariff()); order.attachSubscription(subscription, NOW); return VpnDelivery.automatic(user, subscription, access(subscription), order, VpnDeliveryType.ACTIVATION_PROVISION, "a".repeat(64), NOW); }
    private TelegramUser user() { return user(10L); }
    private TelegramUser user(long telegramId) { return TelegramUser.builder().id(UUID.randomUUID()).telegramId(telegramId).chatId(telegramId).role(UserRole.USER).createdAt(NOW).updatedAt(NOW).build(); }
    private Subscription subscription(TelegramUser user) { return Subscription.builder().id(UUID.randomUUID()).user(user).tariff(tariff()).status(SubscriptionStatus.ACTIVE).startsAt(NOW).expiresAt(NOW.plusSeconds(60)).activatedByTelegramId(10).activatedAt(NOW).createdAt(NOW).updatedAt(NOW).build(); }
    private VpnAccess access(Subscription subscription) { return VpnAccess.builder().id(UUID.randomUUID()).subscription(subscription).providerName("FAKE").externalAccessId(UUID.randomUUID().toString()).configurationData("fake-vpn://redacted").status(VpnAccessStatus.ACTIVE).issuedAt(NOW).createdAt(NOW).updatedAt(NOW).build(); }
    private VpnTariff tariff() { return VpnTariff.builder().id(UUID.randomUUID()).code("TEST").name("Test").durationDays(30).price(new BigDecimal("1.00")).currency("RUB").active(true).createdAt(NOW).updatedAt(NOW).build(); }
    private PaymentOrder order(TelegramUser user, VpnTariff tariff) { return PaymentOrder.create(user, tariff, PaymentProviderType.FAKE, NOW, Duration.ofHours(1)); }
}
