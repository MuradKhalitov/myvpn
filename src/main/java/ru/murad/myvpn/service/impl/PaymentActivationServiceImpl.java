package ru.murad.myvpn.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.murad.myvpn.client.ProvisionedVpnAccess;
import ru.murad.myvpn.client.VpnExtensionRequest;
import ru.murad.myvpn.client.VpnProvider;
import ru.murad.myvpn.client.VpnProvisionRequest;
import ru.murad.myvpn.config.PaymentProperties;
import ru.murad.myvpn.exception.PaymentActivationResultMismatchException;
import ru.murad.myvpn.exception.PaymentOrderValidationException;
import ru.murad.myvpn.exception.PaymentProviderPermanentException;
import ru.murad.myvpn.exception.PaymentProviderUncertainException;
import ru.murad.myvpn.exception.ThreeXUiException;
import ru.murad.myvpn.exception.ThreeXUiRetryableException;
import ru.murad.myvpn.exception.ThreeXUiUncertainException;
import ru.murad.myvpn.exception.VpnProviderPermanentException;
import ru.murad.myvpn.exception.VpnProviderUncertainException;
import ru.murad.myvpn.service.PaymentActivationFailureCode;
import ru.murad.myvpn.service.PaymentActivationService;
import ru.murad.myvpn.service.PaymentActivationTransactionService;
import ru.murad.myvpn.service.PaymentActivationWorkerResult;
import ru.murad.myvpn.service.PreparedPaymentActivation;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentActivationServiceImpl implements PaymentActivationService {
    private final PaymentActivationTransactionService transactions;
    private final VpnProvider vpnProvider;
    private final PaymentProperties properties;
    private final Clock clock;

    @Override
    public PaymentActivationWorkerResult processPendingActivations(int limit) {
        if (limit <= 0 || limit > 100) throw new IllegalArgumentException("Activation batch limit is invalid");
        Instant now = clock.instant();
        int exhausted = transactions.markExhaustedActivations(now, limit);
        List<PreparedPaymentActivation> claimed = transactions.claimActivations(now, limit);
        Counters counters = new Counters();
        for (PreparedPaymentActivation prepared : claimed) {
            assertNoActiveTransaction();
            validatePreparedActivation(prepared);
            try {
                PreparedPaymentActivation resolved = resolveProvisionTarget(prepared);
                if (resolved == null) {
                    counters.skipped++;
                    continue;
                }
                ProviderCall providerCall = buildProviderCall(resolved);
                ProvisionedVpnAccess result;
                try {
                    result = invokeProviderOnly(providerCall);
                } catch (PaymentProviderUncertainException | VpnProviderUncertainException
                         | ThreeXUiUncertainException | ThreeXUiRetryableException ex) {
                    applyOutcome(counters, transactions.retry(resolved,
                            PaymentActivationFailureCode.VPN_PROVIDER_TRANSIENT.name(), clock.instant()));
                    continue;
                } catch (PaymentProviderPermanentException | VpnProviderPermanentException | ThreeXUiException ex) {
                    applyOutcome(counters, transactions.manualReview(resolved,
                            permanentFailureCode(ex), clock.instant()));
                    continue;
                } catch (RuntimeException unexpectedProviderFailure) {
                    applyOutcome(counters, transactions.retry(resolved,
                            PaymentActivationFailureCode.ACTIVATION_PROVIDER_UNEXPECTED.name(), clock.instant()));
                    continue;
                }

                PaymentActivationTransactionService.PaymentActivationOutcome outcome;
                try {
                    outcome = transactions.complete(resolved, result, clock.instant());
                } catch (PaymentActivationResultMismatchException mismatch) {
                    outcome = transactions.manualReview(resolved,
                            PaymentActivationFailureCode.ACTIVATION_PROVIDER_RESULT_MISMATCH.name(), clock.instant());
                } catch (PaymentOrderValidationException invalidResult) {
                    outcome = transactions.manualReview(resolved,
                            PaymentActivationFailureCode.VPN_PROVIDER_RESULT_INVALID.name(), clock.instant());
                }
                applyOutcome(counters, outcome);
            } catch (RuntimeException infrastructureFailure) {
                log.error("Payment activation infrastructure failure; order remains recoverable, exceptionType={}",
                        infrastructureFailure.getClass().getName(), sanitizedStack(infrastructureFailure));
                counters.infrastructureFailures++;
            }
        }
        return new PaymentActivationWorkerResult(claimed.size(), exhausted, counters.succeeded, counters.retryScheduled,
                counters.manualReview, counters.skipped, counters.infrastructureFailures);
    }

    private PreparedPaymentActivation resolveProvisionTarget(PreparedPaymentActivation prepared) {
        if (prepared.action() != ru.murad.myvpn.service.PaymentActivationAction.PROVISION
                || prepared.targetExpiresAt() != null) {
            return prepared;
        }
        Instant now = clock.instant();
        Instant target = vpnProvider.resolveProvisionTarget(
                new VpnProvisionRequest(prepared.userId(), 0L, null,
                        prepared.stableExternalClientId()),
                prepared.durationDays(), now);
        return transactions.fixProvisionTarget(prepared, target, now).orElse(null);
    }

    private void validatePreparedActivation(PreparedPaymentActivation prepared) {
        if (prepared == null || prepared.action() == null || prepared.userId() == null
                || prepared.stableExternalClientId() == null
                || prepared.stableExternalClientId().isBlank()
                || (prepared.action() == ru.murad.myvpn.service.PaymentActivationAction.EXTEND
                && prepared.targetExpiresAt() == null)) {
            throw new IllegalArgumentException("Prepared payment activation command is invalid");
        }
    }

    private void assertNoActiveTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("VPN provider call must be outside transaction");
        }
    }

    private ProviderCall buildProviderCall(PreparedPaymentActivation prepared) {
        if (prepared == null || prepared.action() == null || prepared.userId() == null
                || prepared.targetExpiresAt() == null || prepared.stableExternalClientId() == null
                || prepared.stableExternalClientId().isBlank()) {
            throw new IllegalArgumentException("Prepared payment activation command is invalid");
        }
        return switch (prepared.action()) {
            case PROVISION -> {
                VpnProvisionRequest request = new VpnProvisionRequest(prepared.userId(), 0L,
                        prepared.targetExpiresAt(), prepared.stableExternalClientId());
                yield () -> vpnProvider.provision(request);
            }
            case EXTEND -> {
                VpnExtensionRequest request = new VpnExtensionRequest(prepared.stableExternalClientId(),
                        prepared.targetExpiresAt());
                yield () -> vpnProvider.extend(request);
            }
        };
    }

    private ProvisionedVpnAccess invokeProviderOnly(ProviderCall call) {
        return call.invoke();
    }

    private String permanentFailureCode(RuntimeException exception) {
        if (exception instanceof VpnProviderPermanentException vpnException
                && vpnException.safeFailureCode() != null) {
            return PaymentActivationFailureCode.VPN_PROVIDER_PERMANENT.name();
        }
        return PaymentActivationFailureCode.VPN_PROVIDER_PERMANENT.name();
    }

    private void applyOutcome(Counters counters, PaymentActivationTransactionService.PaymentActivationOutcome outcome) {
        switch (outcome) {
            case SUCCEEDED -> counters.succeeded++;
            case RETRY_SCHEDULED -> counters.retryScheduled++;
            case MANUAL_REVIEW_REQUIRED -> counters.manualReview++;
            case ALREADY_ACTIVATED, STALE, SKIPPED -> counters.skipped++;
        }
    }

    private RuntimeException sanitizedStack(RuntimeException failure) {
        RuntimeException safe = new RuntimeException("Payment activation infrastructure failure");
        safe.setStackTrace(failure.getStackTrace());
        return safe;
    }

    @FunctionalInterface
    private interface ProviderCall {
        ProvisionedVpnAccess invoke();
    }

    private static final class Counters {
        private int succeeded;
        private int retryScheduled;
        private int manualReview;
        private int skipped;
        private int infrastructureFailures;

    }
}
