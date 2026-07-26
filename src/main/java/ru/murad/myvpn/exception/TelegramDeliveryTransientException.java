package ru.murad.myvpn.exception;

import java.time.Duration;
import ru.murad.myvpn.model.VpnDeliveryFailureCode;

public class TelegramDeliveryTransientException extends RuntimeException {
    private final Duration retryAfter;
    private final VpnDeliveryFailureCode failureCode;
    public TelegramDeliveryTransientException() { this(null, VpnDeliveryFailureCode.TELEGRAM_UNAVAILABLE); }
    public TelegramDeliveryTransientException(Duration retryAfter) { this(retryAfter, VpnDeliveryFailureCode.TELEGRAM_RATE_LIMITED); }
    public TelegramDeliveryTransientException(Duration retryAfter, VpnDeliveryFailureCode failureCode) { super("Telegram delivery transient failure"); this.retryAfter = retryAfter; this.failureCode = failureCode; }
    public Duration retryAfter() { return retryAfter; }
    public VpnDeliveryFailureCode safeFailureCode() { return failureCode; }
}
