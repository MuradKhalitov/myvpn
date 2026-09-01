package ru.murad.myvpn.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.murad.myvpn.config.PaymentProperties;
import ru.murad.myvpn.dto.*;
import ru.murad.myvpn.exception.*;
import ru.murad.myvpn.client.PaymentProvider;
import ru.murad.myvpn.model.PaymentStatus;
import ru.murad.myvpn.model.PaymentActivationStatus;
import ru.murad.myvpn.service.*;
import java.time.Clock;

@Service
@RequiredArgsConstructor
public class PaymentVerificationServiceImpl implements PaymentVerificationService {
    private final PaymentVerificationTransactionService transactions;
    private final PaymentProviderRegistry providers;
    private final ProviderPaymentValidator validator;
    private final PaymentProperties properties;
    private final Clock clock;

    @Override
    public PaymentVerificationResult verifyCurrentPayment(java.util.UUID accountId) {
        var preparation = transactions.prepare(accountId, clock.instant(), properties.verification().minInterval());
        if (preparation.immediateResult() != null) return preparation.immediateResult();
        var expected = preparation.prepared();
        PaymentProvider provider = providers.resolve(expected.provider());
        ProviderPayment actual;
        try {
            actual = provider.getPayment(expected.providerPaymentId());
        } catch (PaymentNotFoundException notFound) {
            return transactions.manualReview(expected, "PROVIDER_PAYMENT_NOT_FOUND", clock.instant());
        } catch (PaymentProviderRetryableException retryable) {
            return transactions.retryLater(expected, retryable.retryAfter()
                    .orElse(properties.verification().minInterval()), clock.instant());
        } catch (PaymentProviderUncertainException | PaymentProviderPermanentException ex) {
            return new PaymentVerificationResult(PaymentVerificationOutcome.PROVIDER_UNAVAILABLE, PaymentStatus.PENDING, PaymentActivationStatus.NOT_READY, null, null);
        }
        return afterProviderGet(expected, actual);
    }

    @Override
    public PaymentVerificationResult verifyProviderPayment(String providerPaymentId) {
        var preparation = transactions.prepareByProviderPaymentId(providerPaymentId, clock.instant());
        if (preparation.immediateResult() != null) return preparation.immediateResult();
        var expected = preparation.prepared();
        try {
            return afterProviderGet(expected, providers.resolve(expected.provider())
                    .getPayment(expected.providerPaymentId()));
        } catch (PaymentProviderRetryableException retryable) {
            return transactions.retryLater(expected, retryable.retryAfter()
                    .orElse(properties.verification().minInterval()), clock.instant());
        } catch (PaymentNotFoundException exception) {
            return transactions.manualReview(expected, "PROVIDER_PAYMENT_NOT_FOUND", clock.instant());
        } catch (PaymentProviderUncertainException | PaymentProviderPermanentException exception) {
            return new PaymentVerificationResult(PaymentVerificationOutcome.PROVIDER_UNAVAILABLE,
                    PaymentStatus.PENDING, PaymentActivationStatus.NOT_READY, null, null);
        }
    }

    private PaymentVerificationResult afterProviderGet(
            PreparedPaymentVerification expected,
            ProviderPayment actual
    ) {
        try {
            validator.validate(expected, actual, clock.instant());
        } catch (ProviderPaymentValidationException invalid) {
            if (invalid.manualReview()) {
                return transactions.manualReview(
                        expected, invalid.safeFailureCode(), clock.instant());
            }
            return new PaymentVerificationResult(
                    PaymentVerificationOutcome.PROVIDER_RESULT_UNCERTAIN,
                    PaymentStatus.PENDING, PaymentActivationStatus.NOT_READY, null, null);
        }
        return transactions.apply(expected, actual, clock.instant());
    }
}
