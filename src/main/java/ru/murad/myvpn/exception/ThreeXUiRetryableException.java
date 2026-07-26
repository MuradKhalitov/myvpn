package ru.murad.myvpn.exception;

public class ThreeXUiRetryableException extends ThreeXUiException {

    public ThreeXUiRetryableException(String message) {
        this(VpnProviderFailureCode.PROVIDER_UNAVAILABLE, null, message);
    }

    public ThreeXUiRetryableException(String message, Throwable cause) {
        super(VpnProviderFailureCode.PROVIDER_UNAVAILABLE, true, null, message, cause);
    }

    public ThreeXUiRetryableException(
            VpnProviderFailureCode failureCode,
            java.time.Duration retryAfter,
            String safeMessage
    ) {
        super(failureCode, true, retryAfter, safeMessage);
    }
}
