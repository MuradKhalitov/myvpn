package ru.murad.myvpn.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import ru.murad.myvpn.exception.PaymentOrderValidationException;
import ru.murad.myvpn.exception.PaymentStateTransitionException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentOrderActivationDomainTest {
    private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");

    @Test
    void toStringDoesNotExposeIdentifiersOrActivationSecrets() {
        String rendered = new PaymentOrder().toString();
        assertThat(rendered).doesNotContain("id=", "userId", "providerPaymentId",
                "idempotenceKey", "activationClaimToken", "failureCode");
    }

    @Test
    void succeededPendingOrderCanBeClaimed() {
        PaymentOrder order = succeeded();
        long generation = order.claimActivation(UUID.randomUUID(), NOW.plusSeconds(120), NOW, 5);
        assertThat(generation).isEqualTo(1);
        assertThat(order.getActivationStatus()).isEqualTo(PaymentActivationStatus.PROCESSING);
        assertThat(order.getActivationAttempts()).isEqualTo(1);
    }

    @Test
    void retryRequiredOrderCanBeClaimedAgain() {
        PaymentOrder order = processing();
        UUID token = order.getActivationClaimToken();
        long generation = order.getActivationGeneration();
        order.releaseForRetry(token, generation, NOW.plusSeconds(1));
        order.claimActivation(UUID.randomUUID(), NOW.plusSeconds(120), NOW.plusSeconds(2), 5);
        assertThat(order.getActivationAttempts()).isEqualTo(2);
    }

    @Test
    void reconciliationRequiredOrderCanBeClaimedAgain() {
        PaymentOrder order = processing();
        UUID token = order.getActivationClaimToken();
        long generation = order.getActivationGeneration();
        order.markReconciliationRequired(token, generation, NOW.plusSeconds(1));
        order.claimActivation(UUID.randomUUID(), NOW.plusSeconds(120), NOW.plusSeconds(2), 5);
        assertThat(order.getActivationStatus()).isEqualTo(PaymentActivationStatus.PROCESSING);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"NEW", "CREATING", "PENDING", "CANCELED", "FAILED", "EXPIRED", "MANUAL_REVIEW_REQUIRED"})
    void nonSucceededPaymentCannotBeClaimed(PaymentStatus status) {
        PaymentOrder order = inPaymentStatus(status);
        assertThatThrownBy(() -> order.claimActivation(UUID.randomUUID(), NOW.plusSeconds(60), NOW, 5))
                .isInstanceOf(PaymentStateTransitionException.class);
    }

    @Test
    void activatedOrderCannotBeClaimed() {
        PaymentOrder order = processing();
        order.markActivated(order.getActivationClaimToken(), order.getActivationGeneration(), NOW.plusSeconds(1));
        assertThatThrownBy(() -> order.claimActivation(UUID.randomUUID(), NOW.plusSeconds(60), NOW, 5))
                .isInstanceOf(PaymentStateTransitionException.class);
    }

    @Test
    void manualReviewActivationCannotBeClaimed() {
        PaymentOrder order = processing();
        order.markActivationManualReviewRequired(order.getActivationClaimToken(), order.getActivationGeneration(), NOW.plusSeconds(1));
        assertThatThrownBy(() -> order.claimActivation(UUID.randomUUID(), NOW.plusSeconds(60), NOW, 5))
                .isInstanceOf(PaymentStateTransitionException.class);
    }

    @Test
    void expiredLeaseReclaimAdvancesGenerationAndChangesToken() {
        PaymentOrder order = processing();
        UUID oldToken = order.getActivationClaimToken();
        long oldGeneration = order.getActivationGeneration();
        Instant target = NOW.plusSeconds(30L * 86400);
        order.fixActivationTargetExpiresAt(oldToken, oldGeneration, target, NOW);
        UUID newToken = UUID.randomUUID();
        long newGeneration = order.reclaimExpiredActivation(NOW.plusSeconds(120), Duration.ofMinutes(2), newToken, 5);
        assertThat(newGeneration).isGreaterThan(oldGeneration);
        assertThat(order.getActivationClaimToken()).isEqualTo(newToken);
        assertThat(order.getActivationTargetExpiresAt()).isEqualTo(target);
    }

    @Test
    void activeLeaseCannotBeReclaimed() {
        PaymentOrder order = processing();
        assertThatThrownBy(() -> order.reclaimExpiredActivation(NOW.plusSeconds(1), Duration.ofMinutes(2), UUID.randomUUID(), 5))
                .isInstanceOf(PaymentStateTransitionException.class);
    }

    @Test
    void claimAttemptLimitIsInclusive() {
        PaymentOrder order = succeeded();
        order.claimActivation(UUID.randomUUID(), NOW.plusSeconds(60), NOW, 1);
        assertThatThrownBy(() -> order.reclaimExpiredActivation(NOW.plusSeconds(120), Duration.ofMinutes(1), UUID.randomUUID(), 1))
                .isInstanceOf(PaymentStateTransitionException.class);
    }

    @Test
    void generationOverflowDoesNotMutateClaim() {
        PaymentOrder order = succeeded();
        ReflectionTestUtils.setField(order, "activationGeneration", Long.MAX_VALUE);
        State before = state(order);
        assertThatThrownBy(() -> order.claimActivation(UUID.randomUUID(), NOW.plusSeconds(60), NOW, 5))
                .isInstanceOf(PaymentStateTransitionException.class);
        assertThat(state(order)).isEqualTo(before);
    }

    @Test
    void leaseOverflowIsDomainValidationAndAtomic() {
        PaymentOrder order = succeeded();
        State before = state(order);
        assertThatThrownBy(() -> order.claimActivation(UUID.randomUUID(), Instant.MAX, Instant.MAX, 5))
                .isInstanceOf(PaymentOrderValidationException.class);
        assertThat(state(order)).isEqualTo(before);
    }

    @Test
    void targetExpiryIsFixedOnlyOnce() {
        PaymentOrder order = processing();
        UUID token = order.getActivationClaimToken();
        long generation = order.getActivationGeneration();
        Instant target = NOW.plusSeconds(30L * 86400);
        order.fixActivationTargetExpiresAt(token, generation, target, NOW);
        assertThatThrownBy(() -> order.fixActivationTargetExpiresAt(token, generation, NOW.plusSeconds(31L * 86400), NOW))
                .isInstanceOf(PaymentStateTransitionException.class);
        assertThat(order.getActivationTargetExpiresAt()).isEqualTo(target);
    }

    @Test
    void targetExpiryOverflowIsValidationAndAtomic() {
        PaymentOrder order = processing();
        UUID token = order.getActivationClaimToken();
        long generation = order.getActivationGeneration();
        State before = state(order);
        assertThatThrownBy(() -> order.fixActivationTargetExpiresAt(token, generation, Instant.MAX, Instant.MAX))
                .isInstanceOf(PaymentOrderValidationException.class);
        assertThat(state(order)).isEqualTo(before);
    }

    @Test
    void retryTimestampBeforeNowIsRejectedWithoutMutation() {
        PaymentOrder order = processing();
        State before = state(order);
        assertThatThrownBy(() -> order.scheduleRetry(order.getActivationClaimToken(), order.getActivationGeneration(), NOW, NOW.minusSeconds(1)))
                .isInstanceOf(PaymentOrderValidationException.class);
        assertThat(state(order)).isEqualTo(before);
    }

    @Test
    void retryTimestampAtInstantMaximumRemainsSafe() {
        PaymentOrder order = processing();
        order.scheduleRetry(order.getActivationClaimToken(), order.getActivationGeneration(), NOW, Instant.MAX);
        assertThat(order.getActivationStatus()).isEqualTo(PaymentActivationStatus.RETRY_REQUIRED);
        assertThat(order.getNextActivationAt()).isEqualTo(Instant.MAX.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    @Test
    void staleTokenCannotCompleteClaim() {
        PaymentOrder order = processing();
        State before = state(order);
        assertThatThrownBy(() -> order.markActivated(UUID.randomUUID(), order.getActivationGeneration(), NOW.plusSeconds(1)))
                .isInstanceOf(PaymentStateTransitionException.class);
        assertThat(state(order)).isEqualTo(before);
    }

    @Test
    void staleGenerationCannotCompleteClaim() {
        PaymentOrder order = processing();
        State before = state(order);
        assertThatThrownBy(() -> order.markActivated(order.getActivationClaimToken(), order.getActivationGeneration() + 1, NOW.plusSeconds(1)))
                .isInstanceOf(PaymentStateTransitionException.class);
        assertThat(state(order)).isEqualTo(before);
    }

    @Test
    void successfulCompletionClearsLeaseAndNextActivation() {
        PaymentOrder order = processing();
        order.scheduleRetry(order.getActivationClaimToken(), order.getActivationGeneration(), NOW, NOW.plusSeconds(60));
        order.claimActivation(UUID.randomUUID(), NOW.plusSeconds(120), NOW.plusSeconds(1), 5);
        order.markActivated(order.getActivationClaimToken(), order.getActivationGeneration(), NOW.plusSeconds(2));
        assertThat(order.getActivationStatus()).isEqualTo(PaymentActivationStatus.ACTIVATED);
        assertThat(order.getActivationLeaseUntil()).isNull();
        assertThat(order.getNextActivationAt()).isNull();
        assertThat(order.getActivationCompletedAt()).isEqualTo(NOW.plusSeconds(2));
    }

    @Test
    void retryAndManualReviewDoNotSetCompletionTimestamp() {
        PaymentOrder retry = processing();
        retry.scheduleRetry(retry.getActivationClaimToken(), retry.getActivationGeneration(), NOW, NOW.plusSeconds(60));
        assertThat(retry.getActivationCompletedAt()).isNull();

        PaymentOrder manual = processing();
        manual.markActivationManualReviewRequired(manual.getActivationClaimToken(), manual.getActivationGeneration(), NOW);
        assertThat(manual.getActivationCompletedAt()).isNull();
    }

    @Test
    void retryCompletionKeepsTargetExpiryAndSetsNextActivation() {
        PaymentOrder order = processing();
        Instant target = NOW.plusSeconds(30L * 86400);
        order.fixActivationTargetExpiresAt(order.getActivationClaimToken(), order.getActivationGeneration(), target, NOW);
        order.scheduleRetry(order.getActivationClaimToken(), order.getActivationGeneration(), NOW, NOW.plusSeconds(60));
        assertThat(order.getActivationTargetExpiresAt()).isEqualTo(target);
        assertThat(order.getNextActivationAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(order.getActivationLeaseUntil()).isNull();
    }

    @Test
    void maxAttemptsMarksSafeManualReview() {
        PaymentOrder order = succeeded();
        ReflectionTestUtils.setField(order, "activationAttempts", Integer.MAX_VALUE);
        ReflectionTestUtils.setField(order, "activationCompletedAt", NOW);
        order.markAttemptsExhausted(NOW);
        assertThat(order.getActivationStatus()).isEqualTo(PaymentActivationStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(order.getSafeFailureCode()).isEqualTo("ACTIVATION_MAX_ATTEMPTS_REACHED");
        assertThat(order.getActivationCompletedAt()).isNull();
    }

    private PaymentOrder processing() {
        PaymentOrder order = succeeded();
        order.claimActivation(UUID.randomUUID(), NOW.plusSeconds(120), NOW, 5);
        return order;
    }

    private PaymentOrder succeeded() {
        PaymentOrder order = PaymentOrder.create(user(), tariff(), PaymentProviderType.FAKE, NOW, Duration.ofHours(1));
        order.markCreating(NOW.plusSeconds(1));
        order.markPending("provider", "https://example.invalid", NOW.plusSeconds(2), null, NOW.plusSeconds(3));
        order.markSucceeded(NOW.plusSeconds(4), NOW.plusSeconds(4));
        return order;
    }

    private PaymentOrder inPaymentStatus(PaymentStatus status) {
        PaymentOrder order = PaymentOrder.create(user(), tariff(), PaymentProviderType.FAKE, NOW, Duration.ofHours(1));
        if (status == PaymentStatus.NEW) return order;
        order.markCreating(NOW.plusSeconds(1));
        if (status == PaymentStatus.CREATING) return order;
        order.markPending("provider", "https://example.invalid", NOW.plusSeconds(2), null, NOW.plusSeconds(3));
        if (status == PaymentStatus.PENDING) return order;
        if (status == PaymentStatus.SUCCEEDED) { order.markSucceeded(NOW.plusSeconds(4), NOW.plusSeconds(4)); return order; }
        if (status == PaymentStatus.CANCELED) { order.markCanceled(NOW.plusSeconds(4)); return order; }
        if (status == PaymentStatus.EXPIRED) { order.markExpired(NOW.plusSeconds(4)); return order; }
        if (status == PaymentStatus.FAILED) { order.markFailed("SAFE", NOW.plusSeconds(4)); return order; }
        order.markPaymentManualReviewRequired("SAFE", NOW.plusSeconds(4));
        return order;
    }

    private TelegramUser user() {
        return TelegramUser.builder().id(UUID.randomUUID()).telegramId(1L).chatId(1L).role(UserRole.USER)
                .createdAt(NOW).updatedAt(NOW).build();
    }

    private VpnTariff tariff() {
        return VpnTariff.builder().id(UUID.randomUUID()).code("T").name("Tariff").durationDays(30)
                .price(new BigDecimal("90.00")).currency("RUB").active(true).createdAt(NOW).updatedAt(NOW).build();
    }

    private State state(PaymentOrder order) {
        return new State(order.getActivationStatus(), order.getActivationAttempts(), order.getActivationGeneration(),
                order.getActivationClaimToken(), order.getActivationLeaseUntil(), order.getActivationTargetExpiresAt(),
                order.getNextActivationAt(), order.getSafeFailureCode(), order.getActivationCompletedAt(), order.getUpdatedAt());
    }

    private record State(PaymentActivationStatus status, Integer attempts, long generation, UUID token,
                         Instant lease, Instant target, Instant next, String failure, Instant completed, Instant updated) {}
}
