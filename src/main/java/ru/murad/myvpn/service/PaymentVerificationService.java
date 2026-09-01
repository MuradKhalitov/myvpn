package ru.murad.myvpn.service;
import java.util.UUID;
import ru.murad.myvpn.dto.PaymentVerificationResult;
public interface PaymentVerificationService {
    PaymentVerificationResult verifyCurrentPayment(UUID accountId);
    PaymentVerificationResult verifyProviderPayment(String providerPaymentId);
}
