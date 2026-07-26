package ru.murad.myvpn.exception;

import java.time.Duration;
import java.util.Objects;

/**
 * Provider-facing error with a safe classification. The exception deliberately
 * never retains a raw 3x-ui response, request, cookie, credential, or VLESS URI.
 */
public class ThreeXUiException extends RuntimeException {

    private final VpnProviderFailureCode failureCode;
    private final boolean retryable;
    private final Duration retryAfter;

    public ThreeXUiException(String safeMessage) {
        this(VpnProviderFailureCode.UNKNOWN_PROVIDER_ERROR, false, null, safeMessage, null);
    }

    public ThreeXUiException(String safeMessage, Throwable cause) {
        this(VpnProviderFailureCode.UNKNOWN_PROVIDER_ERROR, false, null, safeMessage, cause);
    }

    public ThreeXUiException(VpnProviderFailureCode failureCode, String safeMessage) {
        this(failureCode, false, null, safeMessage, null);
    }

    protected ThreeXUiException(
            VpnProviderFailureCode failureCode,
            boolean retryable,
            Duration retryAfter,
            String safeMessage
    ) {
        this(failureCode, retryable, retryAfter, safeMessage, null);
    }

    protected ThreeXUiException(
            VpnProviderFailureCode failureCode,
            boolean retryable,
            Duration retryAfter,
            String safeMessage,
            Throwable cause
    ) {
        super(Objects.requireNonNull(safeMessage, "safeMessage"), cause);
        this.failureCode = Objects.requireNonNull(failureCode, "failureCode");
        this.retryable = retryable;
        this.retryAfter = retryAfter != null && !retryAfter.isNegative() && !retryAfter.isZero()
                ? retryAfter : null;
    }

    public VpnProviderFailureCode safeFailureCode() { return failureCode; }
    public boolean retryable() { return retryable; }
    public Duration retryAfter() { return retryAfter; }

    @Override
    public String toString() {
        return "ThreeXUiException[code=" + failureCode + ", retryable=" + retryable + "]";
    }
}
