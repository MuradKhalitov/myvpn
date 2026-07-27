package ru.murad.myvpn.exception;

import java.time.Duration;
import java.util.Optional;

public class PaymentProviderRetryableException extends PaymentProviderUncertainException {
    private final Duration retryAfter;

    public PaymentProviderRetryableException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public Optional<Duration> retryAfter() { return Optional.ofNullable(retryAfter); }
}
