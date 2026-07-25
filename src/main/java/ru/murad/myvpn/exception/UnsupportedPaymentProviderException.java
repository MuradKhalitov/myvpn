package ru.murad.myvpn.exception;

import ru.murad.myvpn.model.PaymentProviderType;

public class UnsupportedPaymentProviderException extends IllegalStateException {

    public UnsupportedPaymentProviderException(PaymentProviderType type) {
        super("Payment provider is not supported: " + type);
    }
}
