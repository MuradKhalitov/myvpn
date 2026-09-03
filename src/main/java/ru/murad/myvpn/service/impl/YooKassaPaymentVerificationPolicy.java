package ru.murad.myvpn.service.impl;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.murad.myvpn.dto.PreparedPaymentVerification;
import ru.murad.myvpn.dto.ProviderPayment;
import ru.murad.myvpn.exception.ProviderPaymentValidationException;
import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.config.YooKassaProperties;
import ru.murad.myvpn.service.ProviderPaymentVerificationPolicy;

import java.time.Instant;

@Component
@ConditionalOnProperty(name = "payment.provider", havingValue = "yookassa")
public class YooKassaPaymentVerificationPolicy implements ProviderPaymentVerificationPolicy {
    private final YooKassaProperties properties;

    public YooKassaPaymentVerificationPolicy(YooKassaProperties properties) {
        this.properties = properties;
    }
    @Override
    public PaymentProviderType providerType() {
        return PaymentProviderType.YOOKASSA;
    }

    @Override
    public void validate(PreparedPaymentVerification expected, ProviderPayment actual, Instant now) {
        if (!properties.shopId().equals(actual.recipientAccountId())) {
            throw new ProviderPaymentValidationException("PAYMENT_RECIPIENT_MISMATCH", true);
        }
    }
}
