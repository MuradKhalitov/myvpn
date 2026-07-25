package ru.murad.myvpn.service;

import org.springframework.stereotype.Component;
import ru.murad.myvpn.dto.*;
import ru.murad.myvpn.exception.ProviderPaymentValidationException;
import ru.murad.myvpn.model.ProviderPaymentStatus;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

@Component
public class ProviderPaymentValidator {
    private final ProviderPaymentVerificationPolicyRegistry policies;
    private final Duration skew;
    public ProviderPaymentValidator(ProviderPaymentVerificationPolicyRegistry policies, ru.murad.myvpn.config.PaymentProperties properties) {
        this.policies = policies; this.skew = properties.verification().maxClockSkew();
    }
    public void validate(PreparedPaymentVerification expected, ProviderPayment actual, Instant now) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(now, "now");
        if (actual == null) throw new ProviderPaymentValidationException("PROVIDER_RESULT_UNCERTAIN", false);
        if (actual.providerPaymentId() == null || actual.providerPaymentId().isBlank() || actual.providerPaymentId().length() > 128)
            throw new ProviderPaymentValidationException("PROVIDER_PAYMENT_ID_MISMATCH", true);
        if (!expected.providerPaymentId().equals(actual.providerPaymentId()))
            throw new ProviderPaymentValidationException("PROVIDER_PAYMENT_ID_MISMATCH", true);
        if (actual.status() == null || actual.status() == ProviderPaymentStatus.UNKNOWN)
            throw new ProviderPaymentValidationException("PROVIDER_RESULT_UNCERTAIN", false);
        if (actual.amount() == null || actual.amount().compareTo(expected.amount()) != 0)
            throw new ProviderPaymentValidationException("PAYMENT_AMOUNT_MISMATCH", true);
        if (!"RUB".equals(actual.currency()) || !expected.currency().equals(actual.currency()))
            throw new ProviderPaymentValidationException("PAYMENT_CURRENCY_MISMATCH", true);
        if (actual.paymentOrderIdFromMetadata() == null || !expected.paymentOrderId().equals(actual.paymentOrderIdFromMetadata()))
            throw new ProviderPaymentValidationException("PAYMENT_METADATA_MISMATCH", true);
        boolean consistent = (actual.status() == ProviderPaymentStatus.SUCCEEDED && actual.paid())
                || (actual.status() != ProviderPaymentStatus.SUCCEEDED && !actual.paid());
        if (!consistent) throw new ProviderPaymentValidationException("PROVIDER_STATUS_PAID_MISMATCH", true);
        Instant maxAllowed;
        try { maxAllowed = now.plus(skew); }
        catch (DateTimeException | ArithmeticException ex) {
            throw new ProviderPaymentValidationException("PROVIDER_TIMESTAMP_MISMATCH", true);
        }
        Instant created = micros(actual.createdAt());
        if (created == null || created.isAfter(maxAllowed))
            throw new ProviderPaymentValidationException("PROVIDER_TIMESTAMP_MISMATCH", true);
        Instant paid = micros(actual.paidAt());
        if (actual.status() == ProviderPaymentStatus.SUCCEEDED) {
            if (paid == null || paid.isBefore(created) || paid.isAfter(maxAllowed))
                throw new ProviderPaymentValidationException("PROVIDER_TIMESTAMP_MISMATCH", true);
        } else if (paid != null) {
            throw new ProviderPaymentValidationException("PROVIDER_TIMESTAMP_MISMATCH", true);
        }
        policies.resolve(expected.provider()).validate(expected, actual, now);
    }
    public static Instant micros(Instant value) { return value == null ? null : value.truncatedTo(ChronoUnit.MICROS); }
}
