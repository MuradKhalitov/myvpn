package ru.murad.myvpn.exception;

public class FakePaymentStillExistsException extends IllegalArgumentException {

    public FakePaymentStillExistsException() {
        super("Fake-платёж всё ещё существует, сброс не выполнен");
    }
}
