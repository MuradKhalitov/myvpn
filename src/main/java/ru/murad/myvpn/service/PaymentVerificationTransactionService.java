package ru.murad.myvpn.service;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import ru.murad.myvpn.dto.*;
public interface PaymentVerificationTransactionService {
    PaymentVerificationPreparation prepare(UUID accountId, Instant now, Duration interval);
    PaymentVerificationPreparation prepareByProviderPaymentId(String providerPaymentId, Instant now);
    PaymentVerificationResult apply(PreparedPaymentVerification expected, ProviderPayment actual, Instant now);
    PaymentVerificationResult manualReview(PreparedPaymentVerification expected, String code, Instant now);
    PaymentVerificationResult retryLater(PreparedPaymentVerification expected, Duration delay, Instant now);
}
