package ru.murad.myvpn.exception;

public class PaymentOrderPersistenceException extends IllegalStateException {

    public PaymentOrderPersistenceException() {
        super("Payment order persistence failed");
    }
}
