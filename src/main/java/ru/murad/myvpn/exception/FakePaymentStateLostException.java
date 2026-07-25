package ru.murad.myvpn.exception;

public class FakePaymentStateLostException extends IllegalStateException {

    public FakePaymentStateLostException() {
        super("Fake payment state was lost; ask an administrator to reset the test order");
    }
}
