package ru.murad.myvpn.service;

import org.springframework.stereotype.Service;
import ru.murad.myvpn.client.FakePaymentProvider;
import ru.murad.myvpn.client.PaymentProvider;
import ru.murad.myvpn.exception.UnsupportedPaymentProviderException;
import ru.murad.myvpn.model.PaymentProviderType;

import java.util.Optional;

@Service
public class PaymentProviderRegistry {

    private final Optional<FakePaymentProvider> fakeProvider;

    public PaymentProviderRegistry(Optional<FakePaymentProvider> fakeProvider) {
        this.fakeProvider = fakeProvider;
    }

    public PaymentProvider resolve(PaymentProviderType type) {
        if (type == PaymentProviderType.FAKE) {
            return fakeProvider.<PaymentProvider>map(provider -> provider)
                    .orElseThrow(() -> new UnsupportedPaymentProviderException(type));
        }
        throw new UnsupportedPaymentProviderException(type);
    }
}
