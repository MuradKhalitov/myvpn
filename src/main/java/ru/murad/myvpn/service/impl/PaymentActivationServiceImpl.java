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
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentActivationServiceImpl implements PaymentActivationService {
    private static final Duration PROVIDER_TECHNICAL_LIFETIME = Duration.ofDays(3650);
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
                ProviderCall providerCall = buildProviderCall(prepared);
                ProvisionedVpnAccess result;
                try {
                    result = invokeProviderOnly(providerCall);
                } catch (PaymentProviderUncertainException | VpnProviderUncertainException
                         | ThreeXUiUncertainException | ThreeXUiRetryableException ex) {
                    applyOutcome(counters, transactions.retry(prepared,
                            PaymentActivationFailureCode.VPN_PROVIDER_TRANSIENT.name(), clock.instant()));
                    continue;
                } catch (PaymentProviderPermanentException | VpnProviderPermanentException | ThreeXUiException ex) {
                    applyOutcome(counters, transactions.manualReview(prepared,
                            permanentFailureCode(ex), clock.instant()));
                    continue;
                } catch (RuntimeException unexpectedProviderFailure) {
                    applyOutcome(counters, transactions.retry(prepared,
                            PaymentActivationFailureCode.ACTIVATION_PROVIDER_UNEXPECTED.name(), clock.instant()));
                    continue;
                }

                PaymentActivationTransactionService.PaymentActivationOutcome outcome;
                try {
                    outcome = transactions.complete(prepared, result, clock.instant());
                } catch (PaymentActivationResultMismatchException mismatch) {
                    outcome = transactions.manualReview(prepared,
                            PaymentActivationFailureCode.ACTIVATION_PROVIDER_RESULT_MISMATCH.name(), clock.instant());
                } catch (PaymentOrderValidationException invalidResult) {
                    outcome = transactions.manualReview(prepared,
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

    private void validatePreparedActivation(PreparedPaymentActivation prepared) {
        if (prepared == null || prepared.action() == null || prepared.accountId() == null
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
        if (prepared == null || prepared.action() == null || prepared.accountId() == null
                || prepared.targetExpiresAt() == null || prepared.stableExternalClientId() == null
                || prepared.stableExternalClientId().isBlank()) {
            throw new IllegalArgumentException("Prepared payment activation command is invalid");
        }
        return switch (prepared.action()) {
            case PROVISION -> {
                VpnProvisionRequest request = new VpnProvisionRequest(prepared.accountId(), 0L,
                        clock.instant().plus(PROVIDER_TECHNICAL_LIFETIME), prepared.stableExternalClientId());
                yield () -> vpnProvider.provision(request);
            }
            case ACTIVATE_EXISTING, EXTEND -> () -> new ProvisionedVpnAccess(prepared.vpnProviderName(),
                    prepared.stableExternalClientId(), null, prepared.targetExpiresAt());
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
