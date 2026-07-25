package ru.murad.myvpn.exception;

public class OpenPaymentOrderAlreadyExistsException extends IllegalStateException {

    public OpenPaymentOrderAlreadyExistsException() {
        super("Open payment order already exists");
    }
}
