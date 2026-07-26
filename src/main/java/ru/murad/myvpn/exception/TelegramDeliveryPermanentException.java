package ru.murad.myvpn.exception;

import ru.murad.myvpn.model.VpnDeliveryFailureCode;

public class TelegramDeliveryPermanentException extends RuntimeException {
    private final VpnDeliveryFailureCode failureCode;
    public TelegramDeliveryPermanentException() { this(VpnDeliveryFailureCode.TELEGRAM_REJECTED); }
    public TelegramDeliveryPermanentException(VpnDeliveryFailureCode failureCode) { super("Telegram delivery permanent failure"); this.failureCode = failureCode; }
    public VpnDeliveryFailureCode safeFailureCode() { return failureCode; }
}
