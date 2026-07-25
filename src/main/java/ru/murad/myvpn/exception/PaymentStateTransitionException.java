package ru.murad.myvpn.exception;

public class PaymentStateTransitionException extends IllegalStateException {

    public PaymentStateTransitionException(String message) {
        super(message);
    }
}
