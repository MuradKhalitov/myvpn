package ru.murad.myvpn.exception;

public class PaymentNotFoundException extends IllegalArgumentException {

    public PaymentNotFoundException() {
        super("Payment was not found");
    }
}
