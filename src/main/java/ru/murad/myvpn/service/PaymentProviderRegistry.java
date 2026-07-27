package ru.murad.myvpn.service;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import ru.murad.myvpn.client.FakePaymentProvider;
import ru.murad.myvpn.client.PaymentProvider;
import ru.murad.myvpn.client.YooKassaPaymentProvider;
import ru.murad.myvpn.exception.UnsupportedPaymentProviderException;
import ru.murad.myvpn.model.PaymentProviderType;

import java.util.Optional;

@Service
public class PaymentProviderRegistry {

    private final Optional<FakePaymentProvider> fakeProvider;
    private final Optional<YooKassaPaymentProvider> yooKassaProvider;

    @Autowired
    public PaymentProviderRegistry(Optional<FakePaymentProvider> fakeProvider,
                                   Optional<YooKassaPaymentProvider> yooKassaProvider) {
        this.fakeProvider = fakeProvider;
        this.yooKassaProvider = yooKassaProvider;
    }

    public PaymentProviderRegistry(Optional<FakePaymentProvider> fakeProvider) {
        this(fakeProvider, Optional.empty());
    }

    public PaymentProvider resolve(PaymentProviderType type) {
        if (type == PaymentProviderType.FAKE) {
            return fakeProvider.<PaymentProvider>map(provider -> provider)
                    .orElseThrow(() -> new UnsupportedPaymentProviderException(type));
        }
        if (type == PaymentProviderType.YOOKASSA) {
            return yooKassaProvider.<PaymentProvider>map(provider -> provider)
                    .orElseThrow(() -> new UnsupportedPaymentProviderException(type));
        }
        throw new UnsupportedPaymentProviderException(type);
    }
}
