package ru.murad.myvpn.service.impl;
import org.springframework.stereotype.Component;
import ru.murad.myvpn.dto.*;
import ru.murad.myvpn.exception.ProviderPaymentValidationException;
import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.service.ProviderPaymentVerificationPolicy;
import java.time.Instant;
@Component
public class FakePaymentVerificationPolicy implements ProviderPaymentVerificationPolicy {
    public PaymentProviderType providerType() { return PaymentProviderType.FAKE; }
    public void validate(PreparedPaymentVerification expected, ProviderPayment actual, Instant now) {
        if (!"fake".equals(actual.paymentMethodType()) || actual.recipientAccountId() != null)
            throw new ProviderPaymentValidationException("PAYMENT_METHOD_MISMATCH", true);
    }
}
