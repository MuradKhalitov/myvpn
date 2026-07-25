package ru.murad.myvpn.service;

import org.junit.jupiter.api.Test;
import ru.murad.myvpn.client.FakePaymentProvider;
import ru.murad.myvpn.exception.UnsupportedPaymentProviderException;
import ru.murad.myvpn.model.PaymentProviderType;

import java.time.Clock;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentProviderRegistryTest {

    @Test
    void mustResolveFakeWithoutFallingBackForYooKassa() {
        FakePaymentProvider fake = new FakePaymentProvider(Clock.systemUTC());
        PaymentProviderRegistry registry =
                new PaymentProviderRegistry(Optional.of(fake));

        assertThat(registry.resolve(PaymentProviderType.FAKE)).isSameAs(fake);
        assertThatThrownBy(() -> registry.resolve(PaymentProviderType.YOOKASSA))
                .isInstanceOf(UnsupportedPaymentProviderException.class);
    }
}
