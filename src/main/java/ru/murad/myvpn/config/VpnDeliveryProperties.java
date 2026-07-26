package ru.murad.myvpn.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import ru.murad.myvpn.exception.PaymentOrderValidationException;
import java.time.Duration;

@ConfigurationProperties(prefix = "vpn.delivery")
public record VpnDeliveryProperties(boolean enabled, int batchSize, Duration fixedDelay,
        Duration leaseDuration, int maxAttempts, Duration retryInitialDelay, Duration retryMaxDelay) {
    public VpnDeliveryProperties {
        if (batchSize <= 0 || batchSize > 100 || maxAttempts <= 0 || maxAttempts > 100) throw new PaymentOrderValidationException("VPN delivery limits are invalid");
        if (fixedDelay == null || fixedDelay.isZero() || fixedDelay.isNegative() || leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative() || retryInitialDelay == null || retryInitialDelay.isZero() || retryInitialDelay.isNegative() || retryMaxDelay == null || retryMaxDelay.isZero() || retryMaxDelay.isNegative() || retryMaxDelay.compareTo(retryInitialDelay) < 0) throw new PaymentOrderValidationException("VPN delivery durations are invalid");
    }
}
