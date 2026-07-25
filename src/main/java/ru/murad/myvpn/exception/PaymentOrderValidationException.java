package ru.murad.myvpn.exception;

public class PaymentOrderValidationException extends IllegalArgumentException {

    public PaymentOrderValidationException(String message) {
        super(message);
    }
}
