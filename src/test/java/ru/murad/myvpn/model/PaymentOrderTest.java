package ru.murad.myvpn.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;
import ru.murad.myvpn.exception.PaymentOrderValidationException;
import ru.murad.myvpn.exception.PaymentStateTransitionException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentOrderTest {

    private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");
    private static final Set<String> ALLOWED_PAYMENT_TRANSITIONS = Set.of(
            "NEW->CREATING", "NEW->EXPIRED", "NEW->FAILED",
            "CREATING->PENDING", "CREATING->SUCCEEDED", "CREATING->CANCELED",
            "CREATING->FAILED", "CREATING->MANUAL_REVIEW_REQUIRED",
            "PENDING->SUCCEEDED", "PENDING->CANCELED", "PENDING->EXPIRED",
            "PENDING->FAILED", "PENDING->MANUAL_REVIEW_REQUIRED",
            "MANUAL_REVIEW_REQUIRED->FAILED",
            "SUCCEEDED->SUCCEEDED");
    private static final Set<String> ALLOWED_ACTIVATION_TRANSITIONS = Set.of(
            "NOT_READY->PENDING",
            "PENDING->PROCESSING",
            "PROCESSING->ACTIVATED",
            "PROCESSING->RETRY_REQUIRED",
            "PROCESSING->RECONCILIATION_REQUIRED",
            "PROCESSING->MANUAL_REVIEW_REQUIRED",
            "RETRY_REQUIRED->PROCESSING",
            "RECONCILIATION_REQUIRED->PROCESSING");

    @Test
    void shouldCreateNewOrderWithImmutableTariffSnapshot() {
        VpnTariff tariff = tariff("MONTH", "Monthly", "90.00", "RUB", 30);

        PaymentOrder order = order(tariff);

        assertThat(order.getStatus()).isEqualTo(PaymentStatus.NEW);
        assertThat(order.getActivationStatus())
                .isEqualTo(PaymentActivationStatus.NOT_READY);
        assertThat(order.getAmount()).isEqualByComparingTo("90.00");
        assertThat(order.getCurrency()).isEqualTo("RUB");
        assertThat(order.getTariffCodeSnapshot()).isEqualTo("MONTH");
        assertThat(order.getTariffNameSnapshot()).isEqualTo("Monthly");
        assertThat(order.getDurationDaysSnapshot()).isEqualTo(30);
        assertThat(order.getSubscription()).isNull();
        assertThat(order.getProviderPaymentId()).isNull();
        assertThat(order.getConfirmationUrl()).isNull();
        assertThat(order.getActivationTargetExpiresAt()).isNull();
        assertThat(order.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
    }

    @Test
    void snapshotMustNotDependOnLaterTariffReplacement() {
        PaymentOrder order = order(tariff("MONTH", "Monthly", "90.00", "RUB", 30));
        VpnTariff changed = tariff("MONTH", "Changed", "150.00", "RUB", 60);

        assertThat(changed.getPrice()).isEqualByComparingTo("150.00");
        assertThat(order.getAmount()).isEqualByComparingTo("90.00");
        assertThat(order.getTariffNameSnapshot()).isEqualTo("Monthly");
        assertThat(order.getDurationDaysSnapshot()).isEqualTo(30);
    }

    @Test
    void shouldRejectNonPositiveAmount() {
        assertThatThrownBy(() -> order(
                tariff("MONTH", "Monthly", "0.00", "RUB", 30)))
                .isInstanceOf(PaymentOrderValidationException.class)
                .hasMessage("Amount must be positive");
    }

    @Test
    void shouldRejectNonPositiveDuration() {
        assertThatThrownBy(() -> order(
                tariff("MONTH", "Monthly", "90.00", "RUB", 0)))
                .isInstanceOf(PaymentOrderValidationException.class)
                .hasMessage("Duration must be positive");
    }

    @Test
    void shouldRejectNonRubCurrency() {
        assertThatThrownBy(() -> order(
                tariff("MONTH", "Monthly", "90.00", "USD", 30)))
                .isInstanceOf(PaymentOrderValidationException.class)
                .hasMessage("Currency must be RUB");
    }

    @Test
    void shouldPreserveExactlyRepresentableRubAmount() {
        PaymentOrder order = order(
                tariff("MONTH", "Monthly", "90.000", "RUB", 30));

        assertThat(order.getAmount()).isEqualByComparingTo("90.00");
        assertThat(order.getAmount().scale()).isEqualTo(2);
    }

    @ParameterizedTest
    @MethodSource("equivalentRubAmounts")
    void equivalentRubAmountsMustUseScaleTwo(String value) {
        PaymentOrder order = order(
                tariff("MONTH", "Monthly", value, "RUB", 30));

        assertThat(order.getAmount()).isEqualTo(new BigDecimal("10.00"));
    }

    @Test
    void shouldRejectAmountThatRequiresRounding() {
        assertThatThrownBy(() -> order(
                tariff("MONTH", "Monthly", "90.001", "RUB", 30)))
                .isInstanceOf(PaymentOrderValidationException.class)
                .hasMessage("RUB amount must not require rounding");
    }

    @ParameterizedTest
    @MethodSource("paymentTransitions")
    void paymentTransitionMatrix(
            PaymentStatus from,
            PaymentStatus to,
            boolean allowed
    ) {
        PaymentOrder order = orderInPaymentStatus(from);

        if (allowed) {
            applyPaymentTransition(order, to);
            assertThat(order.getStatus()).isEqualTo(to);
        } else {
            assertThatThrownBy(() -> applyPaymentTransition(order, to))
                    .isInstanceOf(PaymentStateTransitionException.class);
            assertThat(order.getStatus()).isEqualTo(from);
        }
    }

    @Test
    void reserveVerificationOverflowIsAtomicInMemory() {
        PaymentOrder order = orderInPaymentStatus(PaymentStatus.PENDING);
        Instant originalNext = NOW.plusSeconds(30);
        Instant originalUpdated = NOW;
        ReflectionTestUtils.setField(order, "verificationAttempts", Integer.MAX_VALUE);
        ReflectionTestUtils.setField(order, "nextVerificationAt", originalNext);
        ReflectionTestUtils.setField(order, "updatedAt", originalUpdated);

        assertThatThrownBy(() -> order.reserveVerification(NOW.plusSeconds(60), Duration.ofSeconds(5)))
                .isInstanceOf(PaymentOrderValidationException.class);

        assertThat(order.getVerificationAttempts()).isEqualTo(Integer.MAX_VALUE);
        assertThat(order.getNextVerificationAt()).isEqualTo(originalNext);
        assertThat(order.getUpdatedAt()).isEqualTo(originalUpdated);
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(order.getActivationStatus()).isEqualTo(PaymentActivationStatus.NOT_READY);
        assertThat(order.getPaidAt()).isNull();
        assertThat(order.getSafeFailureCode()).isNull();
    }

    @Test
    void reserveVerificationTimestampOverflowIsAtomicInMemory() {
        PaymentOrder order = orderInPaymentStatus(PaymentStatus.PENDING);
        Instant originalNext = NOW.minusSeconds(1);
        Instant originalUpdated = NOW;
        ReflectionTestUtils.setField(order, "nextVerificationAt", originalNext);
        ReflectionTestUtils.setField(order, "updatedAt", originalUpdated);

        assertThatThrownBy(() -> order.reserveVerification(Instant.MAX, Duration.ofSeconds(1)))
                .isInstanceOf(PaymentOrderValidationException.class);

        assertThat(order.getVerificationAttempts()).isZero();
        assertThat(order.getNextVerificationAt()).isEqualTo(originalNext);
        assertThat(order.getUpdatedAt()).isEqualTo(originalUpdated);
    }

    @Test
    void reserveVerificationRejectsInvalidArgumentsWithoutMutation() {
        for (Instant now : new Instant[]{null, NOW}) {
            for (Duration interval : new Duration[]{null, Duration.ZERO, Duration.ofNanos(-1)}) {
                PaymentOrder order = orderInPaymentStatus(PaymentStatus.PENDING);
                Instant originalUpdated = order.getUpdatedAt();
                assertThatThrownBy(() -> order.reserveVerification(now, interval))
                        .isInstanceOf(PaymentOrderValidationException.class);
                assertThat(order.getVerificationAttempts()).isZero();
                assertThat(order.getNextVerificationAt()).isNull();
                assertThat(order.getUpdatedAt()).isEqualTo(originalUpdated);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("activationTransitions")
    void activationTransitionMatrix(
            PaymentActivationStatus from,
            PaymentActivationStatus to,
            boolean allowed
    ) {
        PaymentOrder order = orderInActivationStatus(from);

        if (allowed) {
            applyActivationTransition(order, to);
            assertThat(order.getActivationStatus()).isEqualTo(to);
        } else {
            assertThatThrownBy(() -> applyActivationTransition(order, to))
                    .isInstanceOf(PaymentStateTransitionException.class);
            assertThat(order.getActivationStatus()).isEqualTo(from);
        }
    }

    @Test
    void shouldAllowPaymentPathToPendingAndSucceeded() {
        PaymentOrder order = order();
        order.markCreating(NOW.plusSeconds(1));
        order.markPending(
                "provider-id", "https://example.test/payment", NOW,
                NOW.plusSeconds(3600), NOW.plusSeconds(2));
        order.markSucceeded(NOW.plusSeconds(3), NOW.plusSeconds(4));

        assertThat(order.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(order.getPaidAt()).isEqualTo(NOW.plusSeconds(3));
        assertThat(order.getActivationStatus())
                .isEqualTo(PaymentActivationStatus.PENDING);
    }

    @Test
    void externalPaymentDetailsMustBeImmutableAndIdempotent() {
        PaymentOrder order = creatingOrder();
        Instant localExpiresAt = order.getExpiresAt();
        Instant expiresAt = NOW.plusSeconds(3600);
        order.markPending(
                "provider-id", "https://example.test/payment",
                NOW, expiresAt, NOW.plusSeconds(2));
        Instant updatedAt = order.getUpdatedAt();

        order.markPending(
                "provider-id", "https://example.test/payment",
                NOW, expiresAt, NOW.plusSeconds(3));

        assertThat(order.getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(order.getExpiresAt()).isEqualTo(localExpiresAt);
        assertThat(order.getProviderExpiresAt()).isEqualTo(expiresAt);
        assertThatThrownBy(() -> order.markPending(
                "different", "https://example.test/payment",
                NOW, expiresAt, NOW.plusSeconds(3)))
                .isInstanceOf(PaymentStateTransitionException.class);
        assertThat(order.getProviderPaymentId()).isEqualTo("provider-id");
    }

    @Test
    void markPendingValidationFailureMustNotMutateExternalDataOrLocalExpiry() {
        PaymentOrder order = creatingOrder();
        Instant localExpiresAt = order.getExpiresAt();
        Instant updatedAt = order.getUpdatedAt();

        assertThatThrownBy(() -> order.markPending(
                "provider-id",
                "https://example.test/payment",
                NOW,
                NOW.minusSeconds(1),
                NOW))
                .isInstanceOf(PaymentOrderValidationException.class);

        assertThat(order.getStatus()).isEqualTo(PaymentStatus.CREATING);
        assertThat(order.getProviderPaymentId()).isNull();
        assertThat(order.getConfirmationUrl()).isNull();
        assertThat(order.getProviderCreatedAt()).isNull();
        assertThat(order.getProviderExpiresAt()).isNull();
        assertThat(order.getExpiresAt()).isEqualTo(localExpiresAt);
        assertThat(order.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void shouldAllowDocumentedTerminalPaymentTransitions() {
        PaymentOrder newExpired = order();
        newExpired.markExpired(NOW.plusSeconds(1));
        PaymentOrder creatingCanceled = creatingOrder();
        creatingCanceled.markCanceled(NOW.plusSeconds(2));
        PaymentOrder pendingFailed = pendingOrder();
        pendingFailed.markFailed("SAFE_CODE", NOW.plusSeconds(3));
        PaymentOrder pendingReview = pendingOrder();
        pendingReview.markPaymentManualReviewRequired(
                "SAFE_CODE", NOW.plusSeconds(3));

        assertThat(newExpired.getStatus()).isEqualTo(PaymentStatus.EXPIRED);
        assertThat(creatingCanceled.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(pendingFailed.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(pendingReview.getStatus())
                .isEqualTo(PaymentStatus.MANUAL_REVIEW_REQUIRED);
    }

    @Test
    void shouldRejectForbiddenPaymentTransitions() {
        PaymentOrder succeeded = succeededOrder();
        PaymentOrder canceled = pendingOrder();
        canceled.markCanceled(NOW.plusSeconds(5));
        PaymentOrder expired = order();
        expired.markExpired(NOW.plusSeconds(5));
        PaymentOrder failed = order();
        failed.markFailed("SAFE", NOW.plusSeconds(5));

        assertThatThrownBy(() -> succeeded.markPending(
                "other", "https://example.test/other", NOW,
                NOW.plusSeconds(3600), NOW.plusSeconds(6)))
                .isInstanceOf(PaymentStateTransitionException.class);
        assertThatThrownBy(() -> succeeded.markCanceled(NOW.plusSeconds(6)))
                .isInstanceOf(PaymentStateTransitionException.class);
        assertThatThrownBy(() -> canceled.markSucceeded(NOW, NOW))
                .isInstanceOf(PaymentStateTransitionException.class);
        assertThatThrownBy(() -> expired.markSucceeded(NOW, NOW))
                .isInstanceOf(PaymentStateTransitionException.class);
        assertThatThrownBy(() -> failed.markPending(
                "id", "https://example.test/id", NOW,
                NOW.plusSeconds(3600), NOW))
                .isInstanceOf(PaymentStateTransitionException.class);
    }

    @Test
    void repeatedSuccessMustOnlyAcceptSamePaidAt() {
        PaymentOrder order = succeededOrder();
        Instant paidAt = order.getPaidAt();

        order.markSucceeded(paidAt, NOW.plusSeconds(20));

        assertThat(order.getPaidAt()).isEqualTo(paidAt);
        assertThatThrownBy(() -> order.markSucceeded(
                paidAt.plusSeconds(1), NOW.plusSeconds(21)))
                .isInstanceOf(PaymentStateTransitionException.class);
    }

    @Test
    void claimMustSetLeaseTokenAndIncrementAttempts() {
        PaymentOrder order = succeededOrder();
        UUID token = UUID.randomUUID();

        long generation = order.claimActivation(
                token, NOW.plusSeconds(120), NOW.plusSeconds(10), 5);

        assertThat(order.getActivationStatus())
                .isEqualTo(PaymentActivationStatus.PROCESSING);
        assertThat(order.getActivationClaimToken()).isEqualTo(token);
        assertThat(order.getActivationGeneration()).isEqualTo(generation);
        assertThat(order.getActivationLeaseUntil()).isEqualTo(NOW.plusSeconds(120));
        assertThat(order.getActivationAttempts()).isEqualTo(1);
    }

    @Test
    void eachSuccessfulClaimMustIncrementAttemptsOnce() {
        PaymentOrder order = succeededOrder();
        UUID first = UUID.randomUUID();
        long firstGeneration = order.claimActivation(
                first, NOW.plusSeconds(120), NOW, 5);
        order.releaseForRetry(first, firstGeneration, NOW.plusSeconds(1));

        order.claimActivation(
                UUID.randomUUID(), NOW.plusSeconds(180), NOW.plusSeconds(2), 5);

        assertThat(order.getActivationAttempts()).isEqualTo(2);
    }

    @Test
    void staleClaimMustNotMutateActivation() {
        PaymentOrder order = succeededOrder();
        UUID current = UUID.randomUUID();
        long generation = order.claimActivation(
                current, NOW.plusSeconds(120), NOW, 5);
        Instant lease = order.getActivationLeaseUntil();

        assertThatThrownBy(() -> order.markActivated(
                UUID.randomUUID(), generation, NOW.plusSeconds(1)))
                .isInstanceOf(PaymentStateTransitionException.class);
        assertThatThrownBy(() -> order.fixActivationTargetExpiresAt(
                UUID.randomUUID(), generation,
                NOW.plusSeconds(500), NOW.plusSeconds(1)))
                .isInstanceOf(PaymentStateTransitionException.class);
        assertThat(order.getActivationStatus())
                .isEqualTo(PaymentActivationStatus.PROCESSING);
        assertThat(order.getActivationLeaseUntil()).isEqualTo(lease);
        assertThat(order.getActivationTargetExpiresAt()).isNull();
    }

    @Test
    void shouldAllowDocumentedActivationRetryAndReconciliationPaths() {
        PaymentOrder retry = succeededOrder();
        UUID retryToken = UUID.randomUUID();
        long retryGeneration = retry.claimActivation(
                retryToken, NOW.plusSeconds(120), NOW, 5);
        retry.releaseForRetry(
                retryToken, retryGeneration, NOW.plusSeconds(1));
        retry.claimActivation(
                UUID.randomUUID(), NOW.plusSeconds(180), NOW.plusSeconds(2), 5);

        PaymentOrder reconciliation = succeededOrder();
        UUID reconciliationToken = UUID.randomUUID();
        long reconciliationGeneration = reconciliation.claimActivation(
                reconciliationToken, NOW.plusSeconds(120), NOW, 5);
        reconciliation.markReconciliationRequired(
                reconciliationToken,
                reconciliationGeneration,
                NOW.plusSeconds(1));
        UUID newToken = UUID.randomUUID();
        long newGeneration = reconciliation.claimActivation(
                newToken, NOW.plusSeconds(180), NOW.plusSeconds(2), 5);
        reconciliation.markActivated(newToken, newGeneration, NOW.plusSeconds(3));

        assertThat(retry.getActivationStatus())
                .isEqualTo(PaymentActivationStatus.PROCESSING);
        assertThat(reconciliation.getActivationStatus())
                .isEqualTo(PaymentActivationStatus.ACTIVATED);
    }

    @Test
    void activatedMustBeTerminal() {
        PaymentOrder order = succeededOrder();
        UUID token = UUID.randomUUID();
        long generation = order.claimActivation(
                token, NOW.plusSeconds(120), NOW, 5);
        order.markActivated(token, generation, NOW.plusSeconds(1));

        assertThatThrownBy(() -> order.claimActivation(
                UUID.randomUUID(), NOW.plusSeconds(200), NOW.plusSeconds(2), 5))
                .isInstanceOf(PaymentStateTransitionException.class);
        assertThatThrownBy(() -> order.markActivated(
                token, generation, NOW.plusSeconds(2)))
                .isInstanceOf(PaymentStateTransitionException.class);
    }

    @Test
    void retryAndReconciliationMustCompleteOnlyThroughNewFencedClaim() {
        PaymentOrder retry = succeededOrder();
        UUID firstRetryToken = UUID.randomUUID();
        long firstRetryGeneration = retry.claimActivation(
                firstRetryToken, NOW.plusSeconds(60), NOW, 5);
        retry.releaseForRetry(
                firstRetryToken, firstRetryGeneration, NOW.plusSeconds(1));
        UUID secondRetryToken = UUID.randomUUID();
        long secondRetryGeneration = retry.claimActivation(
                secondRetryToken, NOW.plusSeconds(120), NOW.plusSeconds(2), 5);
        assertThatThrownBy(() -> retry.markActivationManualReviewRequired(
                firstRetryToken, firstRetryGeneration, NOW.plusSeconds(3)))
                .isInstanceOf(PaymentStateTransitionException.class);
        retry.markActivationManualReviewRequired(
                secondRetryToken, secondRetryGeneration, NOW.plusSeconds(3));

        PaymentOrder reconciliation = succeededOrder();
        UUID firstReconciliationToken = UUID.randomUUID();
        long firstReconciliationGeneration = reconciliation.claimActivation(
                firstReconciliationToken, NOW.plusSeconds(60), NOW, 5);
        reconciliation.markReconciliationRequired(
                firstReconciliationToken,
                firstReconciliationGeneration,
                NOW.plusSeconds(1));
        UUID secondReconciliationToken = UUID.randomUUID();
        long secondReconciliationGeneration = reconciliation.claimActivation(
                secondReconciliationToken,
                NOW.plusSeconds(120),
                NOW.plusSeconds(2),
                5);
        assertThatThrownBy(() -> reconciliation.markActivated(
                firstReconciliationToken,
                firstReconciliationGeneration,
                NOW.plusSeconds(3)))
                .isInstanceOf(PaymentStateTransitionException.class);
        reconciliation.markActivated(
                secondReconciliationToken,
                secondReconciliationGeneration,
                NOW.plusSeconds(3));

        assertThat(retry.getActivationStatus())
                .isEqualTo(PaymentActivationStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(reconciliation.getActivationStatus())
                .isEqualTo(PaymentActivationStatus.ACTIVATED);
    }

    @Test
    void activationCompletionApiMustNotExposeNoTokenOverloads() {
        assertThat(Stream.of(PaymentOrder.class.getMethods())
                .filter(method -> Set.of(
                                "markActivated",
                                "releaseForRetry",
                                "markReconciliationRequired",
                                "markActivationManualReviewRequired")
                        .contains(method.getName()))
                .map(method -> List.of(method.getParameterTypes())))
                .allSatisfy(parameters -> assertThat(parameters)
                        .startsWith(UUID.class, long.class));
    }

    @Test
    void targetExpiryMustBeFixedOnlyOnce() {
        PaymentOrder order = succeededOrder();
        UUID token = UUID.randomUUID();
        long generation = order.claimActivation(
                token, NOW.plusSeconds(120), NOW, 5);
        Instant target = NOW.plus(Duration.ofDays(30));

        order.fixActivationTargetExpiresAt(token, generation, target, NOW);
        order.fixActivationTargetExpiresAt(
                token, generation, target, NOW.plusSeconds(1));

        assertThat(order.getActivationTargetExpiresAt()).isEqualTo(target);
        assertThatThrownBy(() -> order.fixActivationTargetExpiresAt(
                token, generation, target.plusSeconds(1), NOW.plusSeconds(2)))
                .isInstanceOf(PaymentStateTransitionException.class);
    }

    @Test
    void targetExpiryMustBeFutureRelativeToOperation() {
        PaymentOrder order = succeededOrder();
        UUID token = UUID.randomUUID();
        long generation = order.claimActivation(
                token, NOW.plusSeconds(120), NOW, 5);

        assertThatThrownBy(() -> order.fixActivationTargetExpiresAt(
                token, generation, NOW, NOW))
                .isInstanceOf(PaymentOrderValidationException.class);
    }

    @Test
    void expiredActivationLeaseMustBeReclaimedWithNewToken() {
        PaymentOrder order = succeededOrder();
        UUID oldToken = UUID.randomUUID();
        long oldGeneration = order.claimActivation(
                oldToken, NOW.plusSeconds(60), NOW, 5);
        Instant target = NOW.plus(Duration.ofDays(30));
        order.fixActivationTargetExpiresAt(
                oldToken, oldGeneration, target, NOW.plusSeconds(1));
        UUID newToken = UUID.randomUUID();

        long newGeneration = order.reclaimExpiredActivation(
                NOW.plusSeconds(60), Duration.ofMinutes(2), newToken, 5);

        assertThat(order.getActivationStatus())
                .isEqualTo(PaymentActivationStatus.PROCESSING);
        assertThat(order.getActivationClaimToken()).isEqualTo(newToken);
        assertThat(order.getActivationLeaseUntil()).isEqualTo(NOW.plusSeconds(180));
        assertThat(order.getActivationAttempts()).isEqualTo(2);
        assertThat(order.getActivationGeneration()).isEqualTo(newGeneration);
        assertThat(order.getActivationTargetExpiresAt()).isEqualTo(target);
        assertThatThrownBy(() -> order.markActivated(
                oldToken, oldGeneration, NOW.plusSeconds(61)))
                .isInstanceOf(PaymentStateTransitionException.class);
        order.markActivated(newToken, newGeneration, NOW.plusSeconds(61));
    }

    @Test
    void activationLeaseCannotBeReclaimedEarlyOrWithSameToken() {
        PaymentOrder order = succeededOrder();
        UUID token = UUID.randomUUID();
        order.claimActivation(token, NOW.plusSeconds(60), NOW, 5);

        assertThatThrownBy(() -> order.reclaimExpiredActivation(
                NOW.plusSeconds(59), Duration.ofMinutes(2), UUID.randomUUID(), 5))
                .isInstanceOf(PaymentStateTransitionException.class);
        assertThat(order.getActivationAttempts()).isEqualTo(1);
        assertThat(order.getActivationClaimToken()).isEqualTo(token);
    }

    @Test
    void reclaimAttemptLimitMustNotPartiallyMutateOrder() {
        PaymentOrder order = succeededOrder();
        UUID token = UUID.randomUUID();
        order.claimActivation(token, NOW.plusSeconds(60), NOW, 1);
        Instant lease = order.getActivationLeaseUntil();

        assertThatThrownBy(() -> order.reclaimExpiredActivation(
                NOW.plusSeconds(61), Duration.ofMinutes(2), UUID.randomUUID(), 1))
                .isInstanceOf(PaymentStateTransitionException.class);
        assertThat(order.getActivationAttempts()).isEqualTo(1);
        assertThat(order.getActivationClaimToken()).isEqualTo(token);
        assertThat(order.getActivationLeaseUntil()).isEqualTo(lease);
    }

    @Test
    void reusedTokenMustNotRestoreAuthorityAfterRetry() {
        PaymentOrder order = succeededOrder();
        UUID token = UUID.randomUUID();
        long oldGeneration = order.claimActivation(
                token, NOW.plusSeconds(60), NOW, 5);
        order.releaseForRetry(token, oldGeneration, NOW.plusSeconds(1));

        long newGeneration = order.claimActivation(
                token, NOW.plusSeconds(120), NOW.plusSeconds(2), 5);

        assertThat(newGeneration).isGreaterThan(oldGeneration);
        assertThatThrownBy(() -> order.markActivated(
                token, oldGeneration, NOW.plusSeconds(3)))
                .isInstanceOf(PaymentStateTransitionException.class);
        order.markActivated(token, newGeneration, NOW.plusSeconds(3));
    }

    @Test
    void olderTokenAndGenerationMustStayFencedAfterReconciliation() {
        PaymentOrder order = succeededOrder();
        UUID firstToken = UUID.randomUUID();
        long firstGeneration = order.claimActivation(
                firstToken, NOW.plusSeconds(60), NOW, 5);
        order.markReconciliationRequired(
                firstToken, firstGeneration, NOW.plusSeconds(1));
        UUID secondToken = UUID.randomUUID();
        long secondGeneration = order.claimActivation(
                secondToken, NOW.plusSeconds(120), NOW.plusSeconds(2), 5);

        assertThatThrownBy(() -> order.markActivated(
                firstToken, firstGeneration, NOW.plusSeconds(3)))
                .isInstanceOf(PaymentStateTransitionException.class);
        assertThatThrownBy(() -> order.markActivated(
                firstToken, secondGeneration, NOW.plusSeconds(3)))
                .isInstanceOf(PaymentStateTransitionException.class);
        order.markActivated(
                secondToken, secondGeneration, NOW.plusSeconds(3));
    }

    @Test
    void reclaimAfterLeaseExpiryMustAdvanceGeneration() {
        PaymentOrder order = succeededOrder();
        UUID oldToken = UUID.randomUUID();
        long oldGeneration = order.claimActivation(
                oldToken, NOW.plusSeconds(60), NOW, 5);
        UUID newToken = UUID.randomUUID();

        long newGeneration = order.reclaimExpiredActivation(
                NOW.plusSeconds(61), Duration.ofMinutes(2), newToken, 5);

        assertThat(newGeneration).isGreaterThan(oldGeneration);
        assertThat(order.getActivationLeaseUntil()).isEqualTo(NOW.plusSeconds(181));
    }

    @Test
    void activationGenerationOverflowMustNotMutateOrder() {
        PaymentOrder order = succeededOrder();
        ReflectionTestUtils.setField(order, "activationGeneration", Long.MAX_VALUE);

        assertUnchanged(order, () -> order.claimActivation(
                UUID.randomUUID(), NOW.plusSeconds(60), NOW, 5));
    }

    @Test
    void claimStateErrorMustTakePriorityOverInvalidLease() {
        PaymentOrder order = order();

        OrderState before = state(order);
        assertThatThrownBy(() -> order.claimActivation(
                UUID.randomUUID(), NOW, NOW, 5))
                .isInstanceOf(PaymentStateTransitionException.class);
        assertThat(state(order)).isEqualTo(before);
    }

    @Test
    void reclaimStateErrorMustTakePriorityOverInvalidLease() {
        PaymentOrder order = succeededOrder();

        OrderState before = state(order);
        assertThatThrownBy(() -> order.reclaimExpiredActivation(
                NOW, Duration.ZERO, UUID.randomUUID(), 5))
                .isInstanceOf(PaymentStateTransitionException.class);
        assertThat(state(order)).isEqualTo(before);
    }

    @Test
    void reconciliationMayReuseUuidOnlyWithNextGeneration() {
        PaymentOrder order = succeededOrder();
        UUID token = UUID.randomUUID();
        long firstGeneration = order.claimActivation(
                token, NOW.plusSeconds(60), NOW, 5);
        order.markReconciliationRequired(
                token, firstGeneration, NOW.plusSeconds(1));

        long secondGeneration = order.claimActivation(
                token, NOW.plusSeconds(120), NOW.plusSeconds(2), 5);

        assertThat(secondGeneration).isEqualTo(firstGeneration + 1);
        assertThatThrownBy(() -> order.markActivated(
                token, firstGeneration, NOW.plusSeconds(3)))
                .isInstanceOf(PaymentStateTransitionException.class);
        order.markActivated(token, secondGeneration, NOW.plusSeconds(3));
    }

    @Test
    void ordinarySecondClaimMustIncrementGenerationExactlyOnce() {
        PaymentOrder order = succeededOrder();
        UUID firstToken = UUID.randomUUID();
        long firstGeneration = order.claimActivation(
                firstToken, NOW.plusSeconds(60), NOW, 5);
        order.releaseForRetry(
                firstToken, firstGeneration, NOW.plusSeconds(1));

        long secondGeneration = order.claimActivation(
                UUID.randomUUID(), NOW.plusSeconds(120), NOW.plusSeconds(2), 5);

        assertThat(secondGeneration).isEqualTo(firstGeneration + 1);
    }

    @Test
    void reclaimMayReuseUuidOnlyWithNextGeneration() {
        PaymentOrder order = succeededOrder();
        UUID token = UUID.randomUUID();
        long firstGeneration = order.claimActivation(
                token, NOW.plusSeconds(60), NOW, 5);

        long secondGeneration = order.reclaimExpiredActivation(
                NOW.plusSeconds(60), Duration.ofMinutes(2), token, 5);

        assertThat(secondGeneration).isEqualTo(firstGeneration + 1);
        assertThatThrownBy(() -> order.markActivated(
                token, firstGeneration, NOW.plusSeconds(61)))
                .isInstanceOf(PaymentStateTransitionException.class);
        order.markActivated(token, secondGeneration, NOW.plusSeconds(61));
    }

    @Test
    void staleClaimMustBeRejectedByEveryFencedOperation() {
        PaymentOrder order = succeededOrder();
        UUID oldToken = UUID.randomUUID();
        long oldGeneration = order.claimActivation(
                oldToken, NOW.plusSeconds(60), NOW, 5);
        UUID currentToken = UUID.randomUUID();
        long currentGeneration = order.reclaimExpiredActivation(
                NOW.plusSeconds(60), Duration.ofMinutes(2), currentToken, 5);
        OrderState before = state(order);

        assertThatThrownBy(() -> order.markActivated(
                oldToken, oldGeneration, NOW.plusSeconds(61)))
                .isInstanceOf(PaymentStateTransitionException.class);
        assertThatThrownBy(() -> order.releaseForRetry(
                oldToken, oldGeneration, NOW.plusSeconds(61)))
                .isInstanceOf(PaymentStateTransitionException.class);
        assertThatThrownBy(() -> order.markReconciliationRequired(
                oldToken, oldGeneration, NOW.plusSeconds(61)))
                .isInstanceOf(PaymentStateTransitionException.class);
        assertThatThrownBy(() -> order.markActivationManualReviewRequired(
                oldToken, oldGeneration, NOW.plusSeconds(61)))
                .isInstanceOf(PaymentStateTransitionException.class);
        assertThatThrownBy(() -> order.fixActivationTargetExpiresAt(
                oldToken, oldGeneration,
                NOW.plus(Duration.ofDays(30)), NOW.plusSeconds(61)))
                .isInstanceOf(PaymentStateTransitionException.class);
        assertThat(state(order)).isEqualTo(before);

        order.markActivated(
                currentToken, currentGeneration, NOW.plusSeconds(61));
    }

    @Test
    void invalidArgumentsMustNeverPartiallyMutateOrder() {
        PaymentOrder payment = order();
        assertUnchanged(payment, () -> payment.markCreating(null));

        PaymentOrder creating = creatingOrder();
        assertUnchanged(creating, () -> creating.markPending(
                "provider-id", "https://example.test/payment", NOW,
                NOW.plusSeconds(3600), null));

        PaymentOrder canceled = pendingOrder();
        assertUnchanged(canceled, () -> canceled.markCanceled(null));
        PaymentOrder expired = pendingOrder();
        assertUnchanged(expired, () -> expired.markExpired(null));
        PaymentOrder failed = pendingOrder();
        assertUnchanged(failed, () -> failed.markFailed("SAFE", null));
        PaymentOrder review = pendingOrder();
        assertUnchanged(review, () ->
                review.markPaymentManualReviewRequired("SAFE", null));

        PaymentOrder success = creatingOrder();
        assertUnchanged(success, () -> success.markSucceeded(null, NOW));
        assertUnchanged(success, () -> success.markSucceeded(NOW, null));

        PaymentOrder pending = succeededOrder();
        assertUnchanged(pending, () -> pending.claimActivation(
                null, NOW.plusSeconds(60), NOW, 5));
        assertUnchanged(pending, () -> pending.claimActivation(
                UUID.randomUUID(), null, NOW, 5));
        assertUnchanged(pending, () -> pending.claimActivation(
                UUID.randomUUID(), NOW.plusSeconds(60), null, 5));

        UUID token = UUID.randomUUID();
        long generation = pending.claimActivation(
                token, NOW.plusSeconds(60), NOW, 5);
        assertUnchanged(pending, () -> pending.markActivated(
                token, generation, null));
        assertUnchanged(pending, () -> pending.releaseForRetry(
                token, generation, null));
        assertUnchanged(pending, () -> pending.markReconciliationRequired(
                token, generation, null));
        assertUnchanged(pending, () -> pending.markActivationManualReviewRequired(
                token, generation, null));
        assertUnchanged(pending, () -> pending.reclaimExpiredActivation(
                NOW.plusSeconds(60), null, UUID.randomUUID(), 5));
        assertUnchanged(pending, () -> pending.reclaimExpiredActivation(
                NOW.plusSeconds(60), Duration.ofMinutes(1), null, 5));
        assertUnchanged(pending, () -> pending.fixActivationTargetExpiresAt(
                token, generation, NOW, NOW));
    }

    @ParameterizedTest
    @MethodSource("validPendingTtls")
    void shouldAcceptPendingTtlWithinMvpLimit(Duration ttl) {
        PaymentOrder order = PaymentOrder.create(
                user(), tariff("MONTH", "Monthly", "90.00", "RUB", 30),
                PaymentProviderType.FAKE, NOW, ttl);

        assertThat(order.getExpiresAt()).isEqualTo(NOW.plus(ttl));
    }

    @ParameterizedTest
    @MethodSource("invalidPendingTtls")
    void shouldRejectInvalidPendingTtl(Duration ttl) {
        assertThatThrownBy(() -> PaymentOrder.create(
                user(), tariff("MONTH", "Monthly", "90.00", "RUB", 30),
                PaymentProviderType.FAKE, NOW, ttl))
                .isInstanceOf(PaymentOrderValidationException.class);
    }

    @Test
    void failureCodeMustBeValidatedBeforeStateMutation() {
        PaymentOrder order = pendingOrder();
        Instant updatedAt = order.getUpdatedAt();
        String sixtyFourCharacters = "A".repeat(64);

        PaymentOrder valid = pendingOrder();
        valid.markFailed(sixtyFourCharacters, NOW.plusSeconds(5));
        assertThat(valid.getSafeFailureCode()).isEqualTo(sixtyFourCharacters);

        assertThatThrownBy(() -> order.markFailed(
                "A".repeat(65), NOW.plusSeconds(5)))
                .isInstanceOf(PaymentOrderValidationException.class);
        assertThatThrownBy(() -> order.markFailed(
                "unsafe-code", NOW.plusSeconds(5)))
                .isInstanceOf(PaymentOrderValidationException.class);
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(order.getUpdatedAt()).isEqualTo(updatedAt);
        assertThat(order.toString()).doesNotContain(sixtyFourCharacters);
    }

    @Test
    void equalityMustUseOnlyNonNullUuid() {
        PaymentOrder first = order();
        PaymentOrder sameId = order();
        ReflectionTestUtils.setField(sameId, "id", first.getId());
        PaymentOrder different = order();

        assertThat(first).isEqualTo(sameId).hasSameHashCodeAs(sameId);
        assertThat(first).isNotEqualTo(different).isNotEqualTo("not an entity");
        int hash = first.hashCode();
        first.markCreating(NOW.plusSeconds(1));
        assertThat(first.hashCode()).isEqualTo(hash);

        PaymentOrder nullIdOne = order();
        PaymentOrder nullIdTwo = order();
        ReflectionTestUtils.setField(nullIdOne, "id", null);
        ReflectionTestUtils.setField(nullIdTwo, "id", null);
        assertThat(nullIdOne).isNotEqualTo(nullIdTwo);
    }

    @Test
    void toStringMustContainOnlySafeFields() {
        PaymentOrder order = pendingOrder();
        List<String> representations = List.of(
                order.toString(),
                String.valueOf(order),
                Objects.toString(order),
                String.format("%s", order));

        assertThat(representations).allSatisfy(text -> assertThat(text)
                        .contains("FAKE", "PENDING")
                        .doesNotContain(
                                order.getId().toString(),
                                "provider-id",
                                order.getIdempotenceKey().toString(),
                                "https://example.test/payment",
                                "SAFE_CODE",
                                "chat-user"));
    }

    private PaymentOrder order() {
        return order(tariff("MONTH", "Monthly", "90.00", "RUB", 30));
    }

    private PaymentOrder order(VpnTariff tariff) {
        return PaymentOrder.create(
                user(), tariff, PaymentProviderType.FAKE, NOW, Duration.ofHours(1));
    }

    private PaymentOrder creatingOrder() {
        PaymentOrder order = order();
        order.markCreating(NOW.plusSeconds(1));
        return order;
    }

    private PaymentOrder pendingOrder() {
        PaymentOrder order = creatingOrder();
        order.markPending(
                "provider-id", "https://example.test/payment", NOW,
                NOW.plusSeconds(3600), NOW.plusSeconds(2));
        return order;
    }

    private PaymentOrder succeededOrder() {
        PaymentOrder order = pendingOrder();
        order.markSucceeded(NOW.plusSeconds(3), NOW.plusSeconds(4));
        return order;
    }

    private TelegramUser user() {
        return TelegramUser.builder()
                .id(UUID.randomUUID())
                .telegramId(100L)
                .chatId(200L)
                .username("chat-user")
                .role(UserRole.USER)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    private VpnTariff tariff(
            String code,
            String name,
            String amount,
            String currency,
            int duration
    ) {
        return VpnTariff.builder()
                .id(UUID.randomUUID())
                .code(code)
                .name(name)
                .durationDays(duration)
                .price(new BigDecimal(amount))
                .currency(currency)
                .active(true)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    private static Stream<Arguments> paymentTransitions() {
        return Stream.of(PaymentStatus.values())
                .flatMap(from -> Stream.of(PaymentStatus.values())
                        .map(to -> Arguments.of(
                                from,
                                to,
                                ALLOWED_PAYMENT_TRANSITIONS.contains(
                                        from + "->" + to))));
    }

    private static Stream<Arguments> activationTransitions() {
        return Stream.of(PaymentActivationStatus.values())
                .flatMap(from -> Stream.of(PaymentActivationStatus.values())
                        .map(to -> Arguments.of(
                                from,
                                to,
                                ALLOWED_ACTIVATION_TRANSITIONS.contains(
                                        from + "->" + to))));
    }

    private static Stream<Duration> validPendingTtls() {
        return Stream.of(
                Duration.ofNanos(1),
                Duration.ofSeconds(1),
                Duration.ofHours(1),
                Duration.ofHours(24));
    }

    private static Stream<String> equivalentRubAmounts() {
        return Stream.of("10", "10.0", "10.00");
    }

    private void assertUnchanged(PaymentOrder order, Runnable invalidOperation) {
        OrderState before = state(order);
        assertThatThrownBy(invalidOperation::run).isInstanceOf(RuntimeException.class);
        assertThat(state(order)).isEqualTo(before);
    }

    private OrderState state(PaymentOrder order) {
        return new OrderState(
                order.getStatus(),
                order.getActivationStatus(),
                order.getActivationClaimToken(),
                order.getActivationGeneration(),
                order.getActivationLeaseUntil(),
                order.getActivationAttempts(),
                order.getActivationTargetExpiresAt(),
                order.getSafeFailureCode(),
                order.getPaidAt(),
                order.getUpdatedAt());
    }

    private record OrderState(
            PaymentStatus status,
            PaymentActivationStatus activationStatus,
            UUID activationClaimToken,
            long activationGeneration,
            Instant activationLeaseUntil,
            Integer activationAttempts,
            Instant activationTargetExpiresAt,
            String safeFailureCode,
            Instant paidAt,
            Instant updatedAt
    ) {
    }

    private static Stream<Duration> invalidPendingTtls() {
        return Stream.of(
                Duration.ZERO,
                Duration.ofNanos(-1),
                Duration.ofHours(24).plusNanos(1),
                null);
    }

    private PaymentOrder orderInPaymentStatus(PaymentStatus status) {
        PaymentOrder order = order();
        switch (status) {
            case NEW -> {
            }
            case CREATING -> order.markCreating(NOW.plusSeconds(1));
            case PENDING -> makePending(order);
            case SUCCEEDED -> {
                makePending(order);
                order.markSucceeded(NOW.plusSeconds(3), NOW.plusSeconds(4));
            }
            case CANCELED -> {
                makePending(order);
                order.markCanceled(NOW.plusSeconds(3));
            }
            case EXPIRED -> order.markExpired(NOW.plusSeconds(1));
            case FAILED -> order.markFailed("SAFE", NOW.plusSeconds(1));
            case MANUAL_REVIEW_REQUIRED -> {
                makePending(order);
                order.markPaymentManualReviewRequired("SAFE", NOW.plusSeconds(3));
            }
        }
        return order;
    }

    private void applyPaymentTransition(PaymentOrder order, PaymentStatus target) {
        switch (target) {
            case NEW -> throw new PaymentStateTransitionException("No transition to NEW");
            case CREATING -> order.markCreating(NOW.plusSeconds(10));
            case PENDING -> order.markPending(
                    "matrix-provider-id", "https://example.test/matrix", NOW,
                    NOW.plusSeconds(3600), NOW.plusSeconds(10));
            case SUCCEEDED -> order.markSucceeded(
                    order.getPaidAt() == null ? NOW.plusSeconds(9) : order.getPaidAt(),
                    NOW.plusSeconds(10));
            case CANCELED -> order.markCanceled(NOW.plusSeconds(10));
            case EXPIRED -> order.markExpired(NOW.plusSeconds(10));
            case FAILED -> order.markFailed("MATRIX", NOW.plusSeconds(10));
            case MANUAL_REVIEW_REQUIRED ->
                    order.markPaymentManualReviewRequired("MATRIX", NOW.plusSeconds(10));
        }
    }

    private PaymentOrder orderInActivationStatus(PaymentActivationStatus status) {
        if (status == PaymentActivationStatus.NOT_READY) {
            return order();
        }
        PaymentOrder order = succeededOrder();
        if (status == PaymentActivationStatus.PENDING) {
            return order;
        }
        UUID token = UUID.randomUUID();
        long generation = order.claimActivation(
                token, NOW.plusSeconds(120), NOW.plusSeconds(5), 10);
        switch (status) {
            case PROCESSING -> {
            }
            case ACTIVATED ->
                    order.markActivated(token, generation, NOW.plusSeconds(6));
            case RETRY_REQUIRED ->
                    order.releaseForRetry(token, generation, NOW.plusSeconds(6));
            case RECONCILIATION_REQUIRED ->
                    order.markReconciliationRequired(
                            token, generation, NOW.plusSeconds(6));
            case MANUAL_REVIEW_REQUIRED ->
                    order.markActivationManualReviewRequired(
                            token, generation, NOW.plusSeconds(6));
            default -> throw new IllegalStateException("Unexpected activation status");
        }
        return order;
    }

    private void applyActivationTransition(
            PaymentOrder order,
            PaymentActivationStatus target
    ) {
        UUID token = order.getActivationClaimToken();
        long generation = order.getActivationGeneration();
        switch (target) {
            case NOT_READY ->
                    throw new PaymentStateTransitionException("No transition to NOT_READY");
            case PENDING -> {
                order.markCreating(NOW.plusSeconds(1));
                order.markSucceeded(NOW.plusSeconds(2), NOW.plusSeconds(3));
            }
            case PROCESSING -> order.claimActivation(
                    UUID.randomUUID(), NOW.plusSeconds(200), NOW.plusSeconds(10), 10);
            case ACTIVATED ->
                    order.markActivated(token, generation, NOW.plusSeconds(10));
            case RETRY_REQUIRED ->
                    order.releaseForRetry(token, generation, NOW.plusSeconds(10));
            case RECONCILIATION_REQUIRED ->
                    order.markReconciliationRequired(
                            token, generation, NOW.plusSeconds(10));
            case MANUAL_REVIEW_REQUIRED ->
                    order.markActivationManualReviewRequired(
                            token, generation, NOW.plusSeconds(10));
        }
    }

    private void makePending(PaymentOrder order) {
        order.markCreating(NOW.plusSeconds(1));
        order.markPending(
                "provider-id", "https://example.test/payment", NOW,
                NOW.plusSeconds(3600), NOW.plusSeconds(2));
    }
}
