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
        Verification verification
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
    }

    public PaymentProperties(PaymentProviderType provider, Duration pendingTtl, URI returnUrl, boolean allowFake) {
        this(provider, pendingTtl, returnUrl, allowFake,
                new Verification(Duration.ofSeconds(5), Duration.ofMinutes(5)));
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

    @Override
    public String toString() {
        return "PaymentProperties[provider=" + provider
                + ", pendingTtl=" + pendingTtl
                + ", allowFake=" + allowFake + "]";
    }
}
