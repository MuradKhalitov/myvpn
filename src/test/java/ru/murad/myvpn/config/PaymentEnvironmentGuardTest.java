package ru.murad.myvpn.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import ru.murad.myvpn.model.PaymentProviderType;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentEnvironmentGuardTest {

    @Test
    void activationDisabledAllowsFakeVpnProvider() {
        assertThatCode(() -> new PaymentEnvironmentGuard(properties(), environment(false, "fake", false)))
                .doesNotThrowAnyException();
    }

    @Test
    void activationEnabledRejectsFakeVpnProviderWithoutExplicitAllowance() {
        assertThatThrownBy(() -> new PaymentEnvironmentGuard(properties(), environment(true, "fake", false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Fake VPN provider");
    }

    @Test
    void activationEnabledAllowsExplicitFakeVpnProviderAllowance() {
        assertThatCode(() -> new PaymentEnvironmentGuard(properties(), environment(true, "fake", true)))
                .doesNotThrowAnyException();
    }

    @Test
    void activationEnabledAllowsConfiguredThreeXUiProvider() {
        MockEnvironment environment = environment(true, "3x-ui", false)
                .withProperty("vpn.three-x-ui.base-url", "https://3x-ui.invalid")
                .withProperty("vpn.three-x-ui.web-base-path", "/panel")
                .withProperty("vpn.three-x-ui.username", "user")
                .withProperty("vpn.three-x-ui.password", "password")
                .withProperty("vpn.three-x-ui.public-host", "vpn.invalid")
                .withProperty("vpn.three-x-ui.inbound-id", "1");

        assertThatCode(() -> new PaymentEnvironmentGuard(properties(), environment))
                .doesNotThrowAnyException();
    }

    @Test
    void activationEnabledRejectsIncompleteThreeXUiProvider() {
        assertThatThrownBy(() -> new PaymentEnvironmentGuard(properties(), environment(true, "3x-ui", false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Required VPN provider setting");
    }

    @Test
    void missingProviderDoesNotSilentlyEnableFakeActivation() {
        assertThatThrownBy(() -> new PaymentEnvironmentGuard(properties(), environment(true, null, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Fake VPN provider");
    }

    @Test
    void productionRejectsFakeProvidersEvenWhenExplicitlyAllowed() {
        MockEnvironment environment = environment(true, "fake", true);
        environment.setActiveProfiles("prod");

        assertThatThrownBy(() -> new PaymentEnvironmentGuard(properties(), environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Fake payment provider is forbidden in production");
    }

    @Test
    void productionRejectsFakeVpnProviderEvenWhenExplicitlyAllowed() {
        MockEnvironment environment = environment(true, "fake", true);
        environment.setActiveProfiles("prod");
        PaymentProperties paymentProperties = new PaymentProperties(PaymentProviderType.YOOKASSA,
                Duration.ofHours(1), URI.create("https://example.invalid"), false);

        assertThatThrownBy(() -> new PaymentEnvironmentGuard(paymentProperties, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Fake VPN provider is forbidden in production and staging");
    }

    @Test
    void stagingAllowsFakePaymentWithExplicitAllowanceAndThreeXUi() {
        MockEnvironment environment = configuredThreeXUiEnvironment();
        environment.setActiveProfiles("staging");

        assertThatCode(() -> new PaymentEnvironmentGuard(properties(), environment))
                .doesNotThrowAnyException();
    }

    @Test
    void stagingRejectsFakePaymentWithoutExplicitAllowance() {
        MockEnvironment environment = configuredThreeXUiEnvironment();
        environment.setActiveProfiles("staging");
        PaymentProperties paymentProperties = new PaymentProperties(PaymentProviderType.FAKE,
                Duration.ofHours(1), URI.create("https://example.invalid"), false);

        assertThatThrownBy(() -> new PaymentEnvironmentGuard(paymentProperties, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Fake payment provider is disabled");
    }

    @Test
    void stagingRejectsFakeVpnProvider() {
        MockEnvironment environment = environment(true, "fake", true);
        environment.setActiveProfiles("staging");
        PaymentProperties paymentProperties = new PaymentProperties(PaymentProviderType.YOOKASSA,
                Duration.ofHours(1), URI.create("https://example.invalid"), false);

        assertThatThrownBy(() -> new PaymentEnvironmentGuard(paymentProperties, environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Fake VPN provider is forbidden in production and staging");
    }

    private MockEnvironment configuredThreeXUiEnvironment() {
        return environment(true, "3x-ui", false)
                .withProperty("vpn.three-x-ui.base-url", "https://3x-ui.invalid")
                .withProperty("vpn.three-x-ui.web-base-path", "/panel")
                .withProperty("vpn.three-x-ui.username", "user")
                .withProperty("vpn.three-x-ui.password", "password")
                .withProperty("vpn.three-x-ui.public-host", "vpn.invalid")
                .withProperty("vpn.three-x-ui.inbound-id", "1");
    }

    private MockEnvironment environment(boolean enabled, String provider, boolean allowFake) {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("payment.activation.enabled", Boolean.toString(enabled))
                .withProperty("vpn.provider.allow-fake", Boolean.toString(allowFake));
        if (provider != null) environment.withProperty("vpn.provider.type", provider);
        return environment;
    }

    private PaymentProperties properties() {
        return new PaymentProperties(PaymentProviderType.FAKE, Duration.ofHours(1),
                URI.create("https://example.invalid"), true);
    }
}
