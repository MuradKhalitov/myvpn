package ru.murad.myvpn.config;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
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

    @Autowired
    public PaymentEnvironmentGuard(PaymentProperties properties, Environment environment) {
        this(properties);
        boolean activationEnabled = environment.getProperty("payment.activation.enabled", Boolean.class, false);
        String vpnProvider = environment.getProperty("vpn.provider.type", "fake");
        boolean allowFakeVpn = environment.getProperty("vpn.provider.allow-fake", Boolean.class, false);
        if (!"fake".equalsIgnoreCase(vpnProvider) && !"3x-ui".equalsIgnoreCase(vpnProvider)) {
            throw new IllegalStateException("Unsupported VPN provider type");
        }
        if (activationEnabled && "fake".equalsIgnoreCase(vpnProvider) && !allowFakeVpn) {
            throw new IllegalStateException("Fake VPN provider is disabled when payment activation is enabled");
        }
        if (activationEnabled && "3x-ui".equalsIgnoreCase(vpnProvider)) {
            require(environment, "vpn.three-x-ui.base-url");
            require(environment, "vpn.three-x-ui.web-base-path");
            require(environment, "vpn.three-x-ui.username");
            require(environment, "vpn.three-x-ui.password");
            require(environment, "vpn.three-x-ui.public-host");
            if (environment.getProperty("vpn.three-x-ui.inbound-id", Integer.class, 0) <= 0)
                throw new IllegalStateException("3x-ui inbound id is required when payment activation is enabled");
        }
    }

    private void require(Environment environment, String key) {
        if (environment.getProperty(key, String.class, "").isBlank())
            throw new IllegalStateException("Required VPN provider setting is missing");
    }
}
