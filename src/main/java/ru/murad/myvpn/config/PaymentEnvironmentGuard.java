package ru.murad.myvpn.config;

import org.springframework.stereotype.Component;
import ru.murad.myvpn.model.PaymentProviderType;

@Component
public class PaymentEnvironmentGuard {

    public PaymentEnvironmentGuard(PaymentProperties properties) {
        if (properties.provider() == PaymentProviderType.FAKE
                && !properties.allowFake()) {
            throw new IllegalStateException(
                    "Fake payment provider is disabled outside local/test");
        }
    }
}
