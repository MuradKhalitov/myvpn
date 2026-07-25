package ru.murad.myvpn.exception;

public class PaymentProviderPermanentException extends RuntimeException {

    public PaymentProviderPermanentException(String safeMessage) {
        super(safeMessage);
    }
}
