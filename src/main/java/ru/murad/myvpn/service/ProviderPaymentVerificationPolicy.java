package ru.murad.myvpn.service;
import ru.murad.myvpn.dto.*;
import java.time.Instant;
public interface ProviderPaymentVerificationPolicy {
    ru.murad.myvpn.model.PaymentProviderType providerType();
    void validate(PreparedPaymentVerification expected, ProviderPayment actual, Instant now);
}
