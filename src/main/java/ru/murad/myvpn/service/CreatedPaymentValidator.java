package ru.murad.myvpn.service;

import org.springframework.stereotype.Component;
import ru.murad.myvpn.client.PaymentConfirmationUrl;
import ru.murad.myvpn.dto.CreatedPayment;
import ru.murad.myvpn.exception.PaymentProviderUncertainException;
import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.model.ProviderPaymentStatus;

import java.time.Duration;
import java.time.Instant;

@Component
public class CreatedPaymentValidator {

    private static final int PROVIDER_PAYMENT_ID_MAX_LENGTH = 128;
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    public void validate(
            PaymentProviderType provider,
            CreatedPayment payment,
            Instant now
    ) {
        if (provider == null || payment == null || now == null) {
            throw uncertain();
        }
        String providerPaymentId = payment.providerPaymentId();
        if (providerPaymentId == null || providerPaymentId.isBlank()
                || providerPaymentId.length() > PROVIDER_PAYMENT_ID_MAX_LENGTH
                || payment.status() != ProviderPaymentStatus.PENDING
                || payment.confirmationUrl() == null
                || payment.providerCreatedAt() == null) {
            throw uncertain();
        }
        Instant latestCreatedAt;
        try {
            latestCreatedAt = now.plus(MAX_CLOCK_SKEW);
        } catch (RuntimeException invalidTime) {
            throw uncertain();
        }
        if (payment.providerCreatedAt().isAfter(latestCreatedAt)) {
            throw uncertain();
        }
        Instant providerExpiresAt = payment.expiresAt();
        if (providerExpiresAt != null
                && (!providerExpiresAt.isAfter(payment.providerCreatedAt())
                || !providerExpiresAt.isAfter(now))) {
            throw uncertain();
        }
        try {
            if (provider == PaymentProviderType.FAKE) {
                PaymentConfirmationUrl.fake(payment.confirmationUrl());
            } else if (provider == PaymentProviderType.YOOKASSA) {
                if (!"https".equalsIgnoreCase(payment.confirmationUrl().getScheme())
                        || payment.confirmationUrl().getHost() == null
                        || payment.confirmationUrl().getHost().isBlank()) {
                    throw uncertain();
                }
            } else {
                throw uncertain();
            }
        } catch (PaymentProviderUncertainException exception) {
            throw exception;
        } catch (RuntimeException invalidUrl) {
            throw uncertain();
        }
    }

    private PaymentProviderUncertainException uncertain() {
        return new PaymentProviderUncertainException(
                "Payment provider returned an invalid creation result");
    }
}
