package ru.murad.myvpn.service;
import ru.murad.myvpn.dto.PaymentVerificationResult;
public interface PaymentVerificationService {
    PaymentVerificationResult verifyCurrentPayment(long telegramId);
    PaymentVerificationResult verifyProviderPayment(String providerPaymentId);
}
