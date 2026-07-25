package ru.murad.myvpn.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
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
        boolean allowFake
) {
    private static final Duration MAX_PENDING_TTL = Duration.ofHours(24);

    public PaymentProperties {
        Objects.requireNonNull(provider, "payment.provider");
        Objects.requireNonNull(returnUrl, "payment.return-url");
        if (pendingTtl == null || pendingTtl.isZero() || pendingTtl.isNegative()
                || pendingTtl.compareTo(MAX_PENDING_TTL) > 0) {
            throw new PaymentOrderValidationException(
                    "Payment pending TTL must be between 1 nanosecond and 24 hours");
        }
    }

    @Override
    public String toString() {
        return "PaymentProperties[provider=" + provider
                + ", pendingTtl=" + pendingTtl
                + ", allowFake=" + allowFake + "]";
    }
}
