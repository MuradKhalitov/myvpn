package ru.murad.myvpn.service;
import ru.murad.myvpn.dto.*;
import java.time.Duration;
import java.time.Instant;
public interface PaymentVerificationTransactionService {
    PaymentVerificationPreparation prepare(long telegramId, Instant now, Duration interval);
    PaymentVerificationPreparation prepareByProviderPaymentId(String providerPaymentId, Instant now);
    PaymentVerificationResult apply(PreparedPaymentVerification expected, ProviderPayment actual, Instant now);
    PaymentVerificationResult manualReview(PreparedPaymentVerification expected, String code, Instant now);
    PaymentVerificationResult retryLater(PreparedPaymentVerification expected, Duration delay, Instant now);
}
