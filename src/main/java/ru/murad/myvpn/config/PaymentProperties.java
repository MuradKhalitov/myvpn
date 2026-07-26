package ru.murad.myvpn.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import ru.murad.myvpn.exception.PaymentOrderValidationException;
import ru.murad.myvpn.model.PaymentProviderType;

import java.time.Duration;
import java.net.URI;
import java.util.Objects;

@ConfigurationProperties(prefix = "payment")
public record PaymentProperties(
        PaymentProviderType provider,
        Duration pendingTtl,
        URI returnUrl,
        boolean allowFake,
        Verification verification,
        Activation activation
) {
    private static final Duration MAX_PENDING_TTL = Duration.ofHours(24);

    @ConstructorBinding
    public PaymentProperties {
        Objects.requireNonNull(provider, "payment.provider");
        Objects.requireNonNull(returnUrl, "payment.return-url");
        if (pendingTtl == null || pendingTtl.isZero() || pendingTtl.isNegative()
                || pendingTtl.compareTo(MAX_PENDING_TTL) > 0) {
            throw new PaymentOrderValidationException(
                    "Payment pending TTL must be between 1 nanosecond and 24 hours");
        }
        if (verification == null) verification = new Verification(Duration.ofSeconds(5), Duration.ofMinutes(5));
        if (activation == null) activation = new Activation(false, Duration.ofSeconds(10), 20,
                Duration.ofMinutes(2), Duration.ofSeconds(30), 5);
    }

    public PaymentProperties(PaymentProviderType provider, Duration pendingTtl, URI returnUrl, boolean allowFake) {
        this(provider, pendingTtl, returnUrl, allowFake,
                new Verification(Duration.ofSeconds(5), Duration.ofMinutes(5)), null);
    }

    public PaymentProperties(PaymentProviderType provider, Duration pendingTtl, URI returnUrl,
                             boolean allowFake, Verification verification) {
        this(provider, pendingTtl, returnUrl, allowFake, verification, null);
    }

    public record Verification(Duration minInterval, Duration maxClockSkew) {
        public Verification {
            if (minInterval == null || minInterval.isZero() || minInterval.isNegative()
                    || minInterval.compareTo(Duration.ofMinutes(5)) > 0)
                throw new PaymentOrderValidationException("Verification interval is invalid");
            if (maxClockSkew == null || maxClockSkew.isNegative()
                    || maxClockSkew.compareTo(Duration.ofMinutes(15)) > 0)
                throw new PaymentOrderValidationException("Verification clock skew is invalid");
        }
    }

    public record Activation(boolean enabled, Duration fixedDelay, int batchSize,
                             Duration leaseDuration, Duration retryDelay, int maxAttempts) {
        public Activation {
            if (fixedDelay == null || fixedDelay.isZero() || fixedDelay.isNegative()
                    || fixedDelay.compareTo(Duration.ofHours(1)) > 0) throw new PaymentOrderValidationException("Activation fixed delay is invalid");
            if (batchSize <= 0 || batchSize > 100) throw new PaymentOrderValidationException("Activation batch size is invalid");
            if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()
                    || leaseDuration.compareTo(Duration.ofMinutes(30)) > 0) throw new PaymentOrderValidationException("Activation lease duration is invalid");
            if (retryDelay == null || retryDelay.isZero() || retryDelay.isNegative()
                    || retryDelay.compareTo(Duration.ofHours(24)) > 0) throw new PaymentOrderValidationException("Activation retry delay is invalid");
            if (maxAttempts <= 0 || maxAttempts > 100) throw new PaymentOrderValidationException("Activation max attempts is invalid");
        }
    }

    @Override
    public String toString() {
        return "PaymentProperties[provider=" + provider
                + ", pendingTtl=" + pendingTtl
                + ", allowFake=" + allowFake + "]";
    }
}
