package ru.murad.myvpn.exception;

public class PaymentActivationResultMismatchException extends PaymentOrderValidationException {

    public PaymentActivationResultMismatchException(String message) {
        super(message);
    }
}
