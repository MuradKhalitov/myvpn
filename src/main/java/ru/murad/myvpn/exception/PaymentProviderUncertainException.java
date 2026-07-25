package ru.murad.myvpn.exception;

public class PaymentProviderUncertainException extends RuntimeException {

    public PaymentProviderUncertainException(String safeMessage) {
        super(safeMessage);
    }
}
