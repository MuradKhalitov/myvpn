package ru.murad.myvpn.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.murad.myvpn.client.ProvisionedVpnAccess;
import ru.murad.myvpn.client.VpnExtensionRequest;
import ru.murad.myvpn.client.VpnProvider;
import ru.murad.myvpn.client.VpnProvisionRequest;
import ru.murad.myvpn.config.PaymentProperties;
import ru.murad.myvpn.exception.PaymentOrderValidationException;
import ru.murad.myvpn.exception.PaymentActivationResultMismatchException;
import ru.murad.myvpn.exception.PaymentProviderPermanentException;
import ru.murad.myvpn.exception.PaymentProviderUncertainException;
import ru.murad.myvpn.exception.VpnProviderPermanentException;
import ru.murad.myvpn.exception.VpnProviderUncertainException;
import ru.murad.myvpn.model.PaymentProviderType;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PaymentActivationServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");
    private static final Instant TARGET = NOW.plus(Duration.ofDays(30));
    private final PaymentActivationTransactionService transactions = mock(PaymentActivationTransactionService.class);
    private final VpnProvider provider = mock(VpnProvider.class);
    private final PaymentActivationService service = new ru.murad.myvpn.service.impl.PaymentActivationServiceImpl(
            transactions, provider, properties(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void provisionSuccessUsesSnapshotIdentityAndCompletesInOrder() {
        PreparedPaymentActivation prepared = prepared(PaymentActivationAction.PROVISION);
        ProvisionedVpnAccess result = result(prepared, "config");
        when(transactions.claimActivations(NOW, 20)).thenReturn(List.of(prepared));
        when(provider.provision(any(VpnProvisionRequest.class))).thenReturn(result);
        when(transactions.complete(prepared, result, NOW)).thenReturn(outcome(PaymentActivationTransactionService.PaymentActivationOutcome.SUCCEEDED));

        assertThat(service.processPendingActivations(20).succeeded()).isEqualTo(1);
        InOrder inOrder = inOrder(transactions, provider);
        inOrder.verify(transactions).markExhaustedActivations(NOW, 20);
        inOrder.verify(transactions).claimActivations(NOW, 20);
        inOrder.verify(provider).provision(new VpnProvisionRequest(prepared.userId(), 0L, TARGET, prepared.stableExternalClientId()));
        inOrder.verify(transactions).complete(prepared, result, NOW);
    }

    @Test
    void providerResolvedProvisionTargetIsFixedBeforeMutation() {
        PreparedPaymentActivation unresolved = prepared(PaymentActivationAction.PROVISION)
                .withTargetExpiresAt(null);
        PreparedPaymentActivation fixed = unresolved.withTargetExpiresAt(TARGET);
        ProvisionedVpnAccess result = result(fixed, "config");
        when(transactions.claimActivations(NOW, 20)).thenReturn(List.of(unresolved));
        when(provider.resolveProvisionTarget(
                new VpnProvisionRequest(unresolved.userId(), 0L, null,
                        unresolved.stableExternalClientId()), 30, NOW)).thenReturn(TARGET);
        when(transactions.fixProvisionTarget(unresolved, TARGET, NOW))
                .thenReturn(Optional.of(fixed));
        when(provider.provision(new VpnProvisionRequest(fixed.userId(), 0L, TARGET,
                fixed.stableExternalClientId()))).thenReturn(result);
        when(transactions.complete(fixed, result, NOW))
                .thenReturn(outcome(PaymentActivationTransactionService.PaymentActivationOutcome.SUCCEEDED));

        assertThat(service.processPendingActivations(20).succeeded()).isEqualTo(1);
        InOrder order = inOrder(transactions, provider);
        order.verify(provider).resolveProvisionTarget(any(), eq(30), eq(NOW));
        order.verify(transactions).fixProvisionTarget(unresolved, TARGET, NOW);
        order.verify(provider).provision(new VpnProvisionRequest(fixed.userId(), 0L, TARGET,
                fixed.stableExternalClientId()));
    }

    @Test
    void extendSuccessUsesExistingStableIdentityAndAbsoluteExpiry() {
        PreparedPaymentActivation prepared = prepared(PaymentActivationAction.EXTEND);
        ProvisionedVpnAccess result = result(prepared, null);
        when(transactions.claimActivations(NOW, 20)).thenReturn(List.of(prepared));
        when(provider.extend(new VpnExtensionRequest(prepared.stableExternalClientId(), TARGET))).thenReturn(result);
        when(transactions.complete(prepared, result, NOW)).thenReturn(outcome(PaymentActivationTransactionService.PaymentActivationOutcome.SUCCEEDED));

        assertThat(service.processPendingActivations(20).succeeded()).isEqualTo(1);
        InOrder inOrder = inOrder(transactions, provider);
        inOrder.verify(provider).extend(new VpnExtensionRequest(prepared.stableExternalClientId(), TARGET));
        inOrder.verify(transactions).complete(prepared, result, NOW);
        verify(provider, never()).provision(any());
    }

    @Test
    void alreadyActivatedResultDoesNotCountAsNewSuccessOrCallProviderAgain() {
        when(transactions.claimActivations(NOW, 20)).thenReturn(List.of());
        PaymentActivationWorkerResult result = service.processPendingActivations(20);
        assertThat(result.claimed()).isZero();
        verifyNoInteractions(provider);
    }

    @Test
    void noEligibleOrderDoesNotCallProvider() {
        when(transactions.claimActivations(NOW, 20)).thenReturn(List.of());
        service.processPendingActivations(20);
        verify(provider, never()).provision(any());
        verify(provider, never()).extend(any());
    }

    @Test
    void activeLeaseIsNotPresentedToProvider() {
        when(transactions.claimActivations(NOW, 20)).thenReturn(List.of());
        service.processPendingActivations(20);
        verifyNoInteractions(provider);
    }

    @Test
    void transientProviderExceptionSchedulesRetry() {
        PreparedPaymentActivation p = prepared(PaymentActivationAction.PROVISION);
        when(transactions.claimActivations(NOW, 20)).thenReturn(List.of(p));
        when(provider.provision(any())).thenThrow(new VpnProviderUncertainException());
        when(transactions.retry(p, "VPN_PROVIDER_TRANSIENT", NOW)).thenReturn(outcome(PaymentActivationTransactionService.PaymentActivationOutcome.RETRY_SCHEDULED));

        assertThat(service.processPendingActivations(20).retryScheduled()).isEqualTo(1);
        InOrder inOrder = inOrder(provider, transactions);
        inOrder.verify(provider).provision(any());
        inOrder.verify(transactions).retry(p, "VPN_PROVIDER_TRANSIENT", NOW);
        verify(transactions, never()).complete(any(), any(), any());
    }

    @Test
    void paymentProviderTransientExceptionSchedulesRetry() {
        PreparedPaymentActivation p = prepared(PaymentActivationAction.PROVISION);
        when(transactions.claimActivations(NOW, 20)).thenReturn(List.of(p));
        when(provider.provision(any())).thenThrow(new PaymentProviderUncertainException("safe"));
        when(transactions.retry(p, "VPN_PROVIDER_TRANSIENT", NOW)).thenReturn(outcome(PaymentActivationTransactionService.PaymentActivationOutcome.RETRY_SCHEDULED));
        assertThat(service.processPendingActivations(20).retryScheduled()).isEqualTo(1);
    }

    @Test
    void permanentProviderExceptionMovesToManualReview() {
        PreparedPaymentActivation p = prepared(PaymentActivationAction.PROVISION);
        when(transactions.claimActivations(NOW, 20)).thenReturn(List.of(p));
        when(provider.provision(any())).thenThrow(new VpnProviderPermanentException("BAD_PROVIDER_RESULT"));
        when(transactions.manualReview(p, "VPN_PROVIDER_PERMANENT", NOW)).thenReturn(outcome(PaymentActivationTransactionService.PaymentActivationOutcome.MANUAL_REVIEW_REQUIRED));
        assertThat(service.processPendingActivations(20).manualReview()).isEqualTo(1);
        verify(transactions).manualReview(p, "VPN_PROVIDER_PERMANENT", NOW);
    }

    @Test
    void paymentProviderPermanentExceptionMovesToManualReview() {
        PreparedPaymentActivation p = prepared(PaymentActivationAction.PROVISION);
        when(transactions.claimActivations(NOW, 20)).thenReturn(List.of(p));
        when(provider.provision(any())).thenThrow(new PaymentProviderPermanentException("PAYMENT_PROVIDER_REJECTED"));
        when(transactions.manualReview(p, "VPN_PROVIDER_PERMANENT", NOW)).thenReturn(outcome(PaymentActivationTransactionService.PaymentActivationOutcome.MANUAL_REVIEW_REQUIRED));
        assertThat(service.processPendingActivations(20).manualReview()).isEqualTo(1);
    }

    @Test
    void malformedResultIsManualReview() {
        PreparedPaymentActivation p = prepared(PaymentActivationAction.PROVISION);
        ProvisionedVpnAccess malformed = new ProvisionedVpnAccess("FAKE", "stable", "config", NOW);
        when(transactions.claimActivations(NOW, 20)).thenReturn(List.of(p));
        when(provider.provision(any())).thenReturn(malformed);
        when(transactions.complete(p, malformed, NOW)).thenThrow(new PaymentOrderValidationException("invalid"));
        when(transactions.manualReview(p, "VPN_PROVIDER_RESULT_INVALID", NOW)).thenReturn(outcome(PaymentActivationTransactionService.PaymentActivationOutcome.MANUAL_REVIEW_REQUIRED));
        assertThat(service.processPendingActivations(20).manualReview()).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("malformedResults")
    void malformedProviderResultsAreFenced(ProvisionedVpnAccess malformed) {
        PreparedPaymentActivation p = prepared(PaymentActivationAction.PROVISION);
        when(transactions.claimActivations(NOW, 20)).thenReturn(List.of(p));
        when(provider.provision(any())).thenReturn(malformed);
        when(transactions.complete(p, malformed, NOW)).thenThrow(new PaymentOrderValidationException("invalid"));
        when(transactions.manualReview(p, "VPN_PROVIDER_RESULT_INVALID", NOW)).thenReturn(outcome(PaymentActivationTransactionService.PaymentActivationOutcome.MANUAL_REVIEW_REQUIRED));
        assertThat(service.processPendingActivations(20).manualReview()).isEqualTo(1);
        verify(transactions).manualReview(p, "VPN_PROVIDER_RESULT_INVALID", NOW);
    }

    @Test
    void extendResultWithDifferentIdentityIsManualReview() {
        PreparedPaymentActivation p = prepared(PaymentActivationAction.EXTEND);
        ProvisionedVpnAccess malformed = new ProvisionedVpnAccess("FAKE", "different-client", null, TARGET);
        when(transactions.claimActivations(NOW, 20)).thenReturn(List.of(p));
        when(provider.extend(any())).thenReturn(malformed);
        when(transactions.complete(p, malformed, NOW)).thenThrow(new PaymentOrderValidationException("identity"));
        when(transactions.manualReview(p, "VPN_PROVIDER_RESULT_INVALID", NOW)).thenReturn(outcome(PaymentActivationTransactionService.PaymentActivationOutcome.MANUAL_REVIEW_REQUIRED));
        assertThat(service.processPendingActivations(20).manualReview()).isEqualTo(1);
    }

    @Test
    void providerResultMismatchUsesDedicatedSafeCode() {
        PreparedPaymentActivation p = prepared(PaymentActivationAction.PROVISION);
        ProvisionedVpnAccess malformed = result(p, "config");
        when(transactions.claimActivations(NOW, 20)).thenReturn(List.of(p));
        when(provider.provision(any())).thenReturn(malformed);
        when(transactions.complete(p, malformed, NOW))
                .thenThrow(new PaymentActivationResultMismatchException("secret-id mismatch"));
        when(transactions.manualReview(p, "ACTIVATION_PROVIDER_RESULT_MISMATCH", NOW))
                .thenReturn(outcome(PaymentActivationTransactionService.PaymentActivationOutcome.MANUAL_REVIEW_REQUIRED));

        assertThat(service.processPendingActivations(20).manualReview()).isEqualTo(1);
        verify(transactions).manualReview(p, "ACTIVATION_PROVIDER_RESULT_MISMATCH", NOW);
    }

    @Test
    void resultWithDifferentExpiryIsManualReview() {
        PreparedPaymentActivation p = prepared(PaymentActivationAction.PROVISION);
        ProvisionedVpnAccess malformed = new ProvisionedVpnAccess("FAKE", p.stableExternalClientId(), "config", TARGET.plusSeconds(1));
        when(transactions.claimActivations(NOW, 20)).thenReturn(List.of(p));
        when(provider.provision(any())).thenReturn(malformed);
        when(transactions.complete(p, malformed, NOW)).thenThrow(new PaymentOrderValidationException("expiry"));
        when(transactions.manualReview(p, "VPN_PROVIDER_RESULT_INVALID", NOW)).thenReturn(outcome(PaymentActivationTransactionService.PaymentActivationOutcome.MANUAL_REVIEW_REQUIRED));
        assertThat(service.processPendingActivations(20).manualReview()).isEqualTo(1);
    }

    @Test
    void tx1PersistenceExceptionPropagatesUnchanged() {
        RuntimeException failure = new IllegalStateException("database unavailable");
        when(transactions.markExhaustedActivations(NOW, 20)).thenThrow(failure);
        assertThatThrownBy(() -> service.processPendingActivations(20)).isSameAs(failure);
        verifyNoInteractions(provider);
    }

    @Test
    void tx2PersistenceExceptionPropagatesUnchanged() {
        PreparedPaymentActivation p = prepared(PaymentActivationAction.PROVISION);
        ProvisionedVpnAccess result = result(p, "config");
        RuntimeException failure = new IllegalStateException("commit failed");
        when(transactions.claimActivations(NOW, 20)).thenReturn(List.of(p));
        when(provider.provision(any())).thenReturn(result);
        when(transactions.complete(p, result, NOW)).thenThrow(failure);
        PaymentActivationWorkerResult workerResult = service.processPendingActivations(20);
        assertThat(workerResult.infrastructureFailures()).isEqualTo(1);
        verify(transactions, never()).retry(any(), anyString(), any());
    }

    @Test
    void validatorExceptionIsNotTreatedAsProviderTransient() {
        PreparedPaymentActivation p = prepared(PaymentActivationAction.PROVISION);
        ProvisionedVpnAccess result = result(p, "config");
        when(transactions.claimActivations(NOW, 20)).thenReturn(List.of(p));
        when(provider.provision(any())).thenReturn(result);
        when(transactions.complete(p, result, NOW)).thenThrow(new PaymentOrderValidationException("invalid"));
        when(transactions.manualReview(p, "VPN_PROVIDER_RESULT_INVALID", NOW)).thenReturn(outcome(PaymentActivationTransactionService.PaymentActivationOutcome.MANUAL_REVIEW_REQUIRED));
        service.processPendingActivations(20);
        verify(transactions, never()).retry(any(), anyString(), any());
    }

    @Test
    void exceptionFromCompleteIsNotCaughtAsProviderError() {
        PreparedPaymentActivation p = prepared(PaymentActivationAction.PROVISION);
        ProvisionedVpnAccess result = result(p, "config");
        RuntimeException failure = new VpnProviderUncertainException();
        when(transactions.claimActivations(NOW, 20)).thenReturn(List.of(p));
        when(provider.provision(any())).thenReturn(result);
        when(transactions.complete(p, result, NOW)).thenThrow(failure);
        PaymentActivationWorkerResult workerResult = service.processPendingActivations(20);
        assertThat(workerResult.infrastructureFailures()).isEqualTo(1);
        verify(transactions, never()).retry(any(), anyString(), any());
    }

    @Test
    void unexpectedProviderRuntimeSchedulesSafeRetry() {
        PreparedPaymentActivation p = prepared(PaymentActivationAction.PROVISION);
        when(transactions.claimActivations(NOW, 20)).thenReturn(List.of(p));
        when(provider.provision(any())).thenThrow(new IllegalStateException("token=https://secret"));
        when(transactions.retry(p, "ACTIVATION_PROVIDER_UNEXPECTED", NOW))
                .thenReturn(outcome(PaymentActivationTransactionService.PaymentActivationOutcome.RETRY_SCHEDULED));

        PaymentActivationWorkerResult workerResult = service.processPendingActivations(20);

        assertThat(workerResult.retryScheduled()).isEqualTo(1);
        assertThat(workerResult.infrastructureFailures()).isZero();
        verify(transactions).retry(p, "ACTIVATION_PROVIDER_UNEXPECTED", NOW);
        verify(transactions, never()).complete(any(), any(), any());
    }

    @Test
    void oneInfrastructureFailureDoesNotStopTheBatch() {
        PreparedPaymentActivation first = prepared(PaymentActivationAction.PROVISION);
        PreparedPaymentActivation second = prepared(PaymentActivationAction.PROVISION);
        ProvisionedVpnAccess firstResult = result(first, "config");
        ProvisionedVpnAccess secondResult = result(second, "config");
        when(transactions.claimActivations(NOW, 20)).thenReturn(List.of(first, second));
        when(provider.provision(any())).thenReturn(firstResult, secondResult);
        when(transactions.complete(first, firstResult, NOW)).thenThrow(new IllegalStateException("commit failed"));
        when(transactions.complete(second, secondResult, NOW))
                .thenReturn(outcome(PaymentActivationTransactionService.PaymentActivationOutcome.SUCCEEDED));

        PaymentActivationWorkerResult workerResult = service.processPendingActivations(20);

        assertThat(workerResult.succeeded()).isEqualTo(1);
        assertThat(workerResult.infrastructureFailures()).isEqualTo(1);
        verify(transactions, never()).retry(first, "ACTIVATION_PROVIDER_UNEXPECTED", NOW);
    }

    @Test
    void providerUsesPreparedSnapshotNotMutableCurrentState() {
        PreparedPaymentActivation p = prepared(PaymentActivationAction.PROVISION);
        when(transactions.claimActivations(NOW, 20)).thenReturn(List.of(p));
        ProvisionedVpnAccess result = result(p, "config");
        when(provider.provision(new VpnProvisionRequest(p.userId(), 0L, TARGET, p.stableExternalClientId()))).thenReturn(result);
        when(transactions.complete(p, result, NOW)).thenReturn(outcome(PaymentActivationTransactionService.PaymentActivationOutcome.SUCCEEDED));
        service.processPendingActivations(20);
        verify(provider).provision(new VpnProvisionRequest(p.userId(), 0L, TARGET, p.stableExternalClientId()));
    }

    @Test
    void provisionCallsOnlyProvision() {
        runSuccess(PaymentActivationAction.PROVISION);
        verify(provider).provision(any());
        verify(provider, never()).extend(any());
    }

    @Test
    void extendCallsOnlyExtend() {
        runSuccess(PaymentActivationAction.EXTEND);
        verify(provider).extend(any());
        verify(provider, never()).provision(any());
    }

    @Test
    void invalidBatchLimitIsRejectedBeforePersistence() {
        assertThatThrownBy(() -> service.processPendingActivations(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.processPendingActivations(101)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(transactions, provider);
    }

    @Test
    void exhaustedOrdersAreReportedAsManualReview() {
        when(transactions.markExhaustedActivations(NOW, 20)).thenReturn(2);
        when(transactions.claimActivations(NOW, 20)).thenReturn(List.of());
        PaymentActivationWorkerResult result = service.processPendingActivations(20);
        assertThat(result.claimed()).isZero();
        assertThat(result.exhausted()).isEqualTo(2);
        assertThat(result.manualReview()).isZero();
    }

    @Test
    void skippedCompletionIsReportedWithoutRetry() {
        PreparedPaymentActivation p = prepared(PaymentActivationAction.PROVISION);
        ProvisionedVpnAccess result = result(p, "config");
        when(transactions.claimActivations(NOW, 20)).thenReturn(List.of(p));
        when(provider.provision(any())).thenReturn(result);
        when(transactions.complete(p, result, NOW)).thenReturn(outcome(PaymentActivationTransactionService.PaymentActivationOutcome.STALE));
        PaymentActivationWorkerResult workerResult = service.processPendingActivations(20);
        assertThat(workerResult.skipped()).isEqualTo(1);
        assertThat(workerResult.retryScheduled()).isZero();
    }

    @Test
    void retryStaleOutcomeCountsOnlyAsSkipped() {
        PreparedPaymentActivation p = prepared(PaymentActivationAction.PROVISION);
        when(transactions.claimActivations(NOW, 20)).thenReturn(List.of(p));
        when(provider.provision(any())).thenThrow(new VpnProviderUncertainException());
        when(transactions.retry(p, "VPN_PROVIDER_TRANSIENT", NOW))
                .thenReturn(outcome(PaymentActivationTransactionService.PaymentActivationOutcome.STALE));

        PaymentActivationWorkerResult result = service.processPendingActivations(20);

        assertThat(result.retryScheduled()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.succeeded() + result.retryScheduled() + result.manualReview()
                + result.skipped() + result.infrastructureFailures()).isEqualTo(result.claimed());
    }

    @Test
    void retryAlreadyActivatedOutcomeCountsOnlyAsSkipped() {
        PreparedPaymentActivation p = prepared(PaymentActivationAction.PROVISION);
        when(transactions.claimActivations(NOW, 20)).thenReturn(List.of(p));
        when(provider.provision(any())).thenThrow(new VpnProviderUncertainException());
        when(transactions.retry(p, "VPN_PROVIDER_TRANSIENT", NOW))
                .thenReturn(outcome(PaymentActivationTransactionService.PaymentActivationOutcome.ALREADY_ACTIVATED));

        PaymentActivationWorkerResult result = service.processPendingActivations(20);

        assertThat(result.retryScheduled()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    void manualReviewStaleOutcomeCountsOnlyAsSkipped() {
        PreparedPaymentActivation p = prepared(PaymentActivationAction.PROVISION);
        when(transactions.claimActivations(NOW, 20)).thenReturn(List.of(p));
        when(provider.provision(any())).thenThrow(new VpnProviderPermanentException("SECRET_TOKEN"));
        when(transactions.manualReview(p, "VPN_PROVIDER_PERMANENT", NOW))
                .thenReturn(outcome(PaymentActivationTransactionService.PaymentActivationOutcome.STALE));

        PaymentActivationWorkerResult result = service.processPendingActivations(20);

        assertThat(result.manualReview()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    void activeTransactionAssertionIsNotProviderRetry() {
        PreparedPaymentActivation p = prepared(PaymentActivationAction.PROVISION);
        when(transactions.claimActivations(NOW, 20)).thenReturn(List.of(p));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> service.processPendingActivations(20))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("outside transaction");
            verifyNoInteractions(provider);
            verify(transactions, never()).retry(any(), anyString(), any());
            verify(transactions, never()).manualReview(any(), anyString(), any());
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    void providerPermanentMessageNeverBecomesFailureCode() {
        PreparedPaymentActivation p = prepared(PaymentActivationAction.PROVISION);
        when(transactions.claimActivations(NOW, 20)).thenReturn(List.of(p));
        when(provider.provision(any())).thenThrow(new VpnProviderPermanentException("DATABASE_PASSWORD"));
        when(transactions.manualReview(p, "VPN_PROVIDER_PERMANENT", NOW))
                .thenReturn(outcome(PaymentActivationTransactionService.PaymentActivationOutcome.MANUAL_REVIEW_REQUIRED));

        service.processPendingActivations(20);

        verify(transactions).manualReview(p, "VPN_PROVIDER_PERMANENT", NOW);
    }

    @Test
    void malformedPreparedCommandPropagatesBeforeProviderBoundary() {
        PreparedPaymentActivation malformed = prepared(null);
        when(transactions.claimActivations(NOW, 20)).thenReturn(List.of(malformed));

        assertThatThrownBy(() -> service.processPendingActivations(20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Prepared payment activation command");

        verifyNoInteractions(provider);
        verify(transactions, never()).retry(any(), anyString(), any());
        verify(transactions, never()).manualReview(any(), anyString(), any());
    }

    @Test
    void blankStableIdentityFailsBeforeProviderBoundary() {
        PreparedPaymentActivation source = prepared(PaymentActivationAction.PROVISION);
        PreparedPaymentActivation malformed = new PreparedPaymentActivation(source.paymentOrderId(), source.userId(),
                source.provider(), source.action(), source.generation(), source.token(), source.durationDays(),
                source.targetExpiresAt(), source.existingSubscriptionId(), source.existingVpnAccessId(), " ",
                source.existingSubscriptionVersion(), source.existingSubscriptionExpiresAt());
        when(transactions.claimActivations(NOW, 20)).thenReturn(List.of(malformed));

        assertThatThrownBy(() -> service.processPendingActivations(20))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(provider);
        verify(transactions, never()).retry(any(), anyString(), any());
        verify(transactions, never()).manualReview(any(), anyString(), any());
    }

    @Test
    void unexpectedExtendRuntimeSchedulesSafeRetry() {
        PreparedPaymentActivation p = prepared(PaymentActivationAction.EXTEND);
        when(transactions.claimActivations(NOW, 20)).thenReturn(List.of(p));
        when(provider.extend(any())).thenThrow(new IllegalStateException("provider runtime"));
        when(transactions.retry(p, "ACTIVATION_PROVIDER_UNEXPECTED", NOW))
                .thenReturn(outcome(PaymentActivationTransactionService.PaymentActivationOutcome.RETRY_SCHEDULED));

        PaymentActivationWorkerResult result = service.processPendingActivations(20);

        assertThat(result.retryScheduled()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
        verify(transactions).retry(p, "ACTIVATION_PROVIDER_UNEXPECTED", NOW);
    }

    @Test
    void mixedBatchKeepsExhaustedSeparateAndConservesClaimOutcomes() {
        PreparedPaymentActivation success = prepared(PaymentActivationAction.PROVISION);
        PreparedPaymentActivation retry = prepared(PaymentActivationAction.PROVISION);
        PreparedPaymentActivation manual = prepared(PaymentActivationAction.PROVISION);
        PreparedPaymentActivation stale = prepared(PaymentActivationAction.PROVISION);
        ProvisionedVpnAccess result = result(success, "config");
        when(transactions.markExhaustedActivations(NOW, 20)).thenReturn(2);
        when(transactions.claimActivations(NOW, 20)).thenReturn(List.of(success, retry, manual, stale));
        when(provider.provision(any())).thenReturn(result)
                .thenThrow(new VpnProviderUncertainException())
                .thenThrow(new VpnProviderPermanentException("SECRET_TOKEN"))
                .thenReturn(result(stale, "config"));
        when(transactions.complete(success, result, NOW))
                .thenReturn(outcome(PaymentActivationTransactionService.PaymentActivationOutcome.SUCCEEDED));
        when(transactions.retry(retry, "VPN_PROVIDER_TRANSIENT", NOW))
                .thenReturn(outcome(PaymentActivationTransactionService.PaymentActivationOutcome.RETRY_SCHEDULED));
        when(transactions.manualReview(manual, "VPN_PROVIDER_PERMANENT", NOW))
                .thenReturn(outcome(PaymentActivationTransactionService.PaymentActivationOutcome.MANUAL_REVIEW_REQUIRED));
        when(transactions.complete(eq(stale), any(), eq(NOW)))
                .thenReturn(outcome(PaymentActivationTransactionService.PaymentActivationOutcome.STALE));

        PaymentActivationWorkerResult resultValue = service.processPendingActivations(20);

        assertThat(resultValue.claimed()).isEqualTo(4);
        assertThat(resultValue.exhausted()).isEqualTo(2);
        assertThat(resultValue.succeeded()).isEqualTo(1);
        assertThat(resultValue.retryScheduled()).isEqualTo(1);
        assertThat(resultValue.manualReview()).isEqualTo(1);
        assertThat(resultValue.skipped()).isEqualTo(1);
        assertThat(resultValue.infrastructureFailures()).isZero();
        assertThat(resultValue.succeeded() + resultValue.retryScheduled() + resultValue.manualReview()
                + resultValue.skipped() + resultValue.infrastructureFailures()).isEqualTo(resultValue.claimed());
    }

    @Test
    void workerResultRejectsInvalidCounterConservation() {
        assertThatThrownBy(() -> new PaymentActivationWorkerResult(1, 0, 0, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void safeResultRenderingDoesNotExposeSensitiveValues() {
        String external = "external-secret";
        String configuration = "vless://secret";
        assertThat(result(prepared(PaymentActivationAction.PROVISION), configuration).toString())
                .doesNotContain(external, configuration);
        assertThat(new PaymentActivationWorkerResult(1, 1, 0, 0, 0).toString())
                .doesNotContain(external, configuration);
    }

    private void runSuccess(PaymentActivationAction action) {
        PreparedPaymentActivation p = prepared(action);
        ProvisionedVpnAccess r = result(p, action == PaymentActivationAction.PROVISION ? "config" : null);
        when(transactions.claimActivations(NOW, 20)).thenReturn(List.of(p));
        if (action == PaymentActivationAction.PROVISION) when(provider.provision(any())).thenReturn(r);
        else when(provider.extend(any())).thenReturn(r);
        when(transactions.complete(p, r, NOW)).thenReturn(outcome(PaymentActivationTransactionService.PaymentActivationOutcome.SUCCEEDED));
        assertThat(service.processPendingActivations(20).succeeded()).isEqualTo(1);
    }

    private static Stream<Arguments> malformedResults() {
        return Stream.of(
                Arguments.of((ProvisionedVpnAccess) null),
                Arguments.of(new ProvisionedVpnAccess("FAKE", " ", "config", TARGET)),
                Arguments.of(new ProvisionedVpnAccess("FAKE", "external", " ", TARGET)),
                Arguments.of(new ProvisionedVpnAccess("FAKE", "external", "config", NOW)),
                Arguments.of(new ProvisionedVpnAccess("FAKE", "external", null, TARGET)));
    }

    private PreparedPaymentActivation prepared(PaymentActivationAction action) {
        return new PreparedPaymentActivation(UUID.randomUUID(), UUID.randomUUID(), PaymentProviderType.FAKE, action,
                3L, UUID.randomUUID(), 30, TARGET,
                action == PaymentActivationAction.EXTEND ? UUID.randomUUID() : null,
                action == PaymentActivationAction.EXTEND ? UUID.randomUUID() : null,
                "stable-client", action == PaymentActivationAction.EXTEND ? 4L : null,
                action == PaymentActivationAction.EXTEND ? NOW : null);
    }

    private ProvisionedVpnAccess result(PreparedPaymentActivation p, String config) {
        return new ProvisionedVpnAccess("FAKE", p.stableExternalClientId(), config, p.targetExpiresAt());
    }

    private PaymentActivationTransactionService.PaymentActivationOutcome outcome(PaymentActivationTransactionService.PaymentActivationOutcome outcome) {
        return outcome;
    }

    private PaymentProperties properties() {
        return new PaymentProperties(PaymentProviderType.FAKE, Duration.ofHours(1), URI.create("https://example.invalid"), true,
                new PaymentProperties.Verification(Duration.ofSeconds(1), Duration.ZERO),
                new PaymentProperties.Activation(true, Duration.ofSeconds(1), 20, Duration.ofMinutes(1), Duration.ofSeconds(1), 3));
    }
}
