package ru.murad.myvpn.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import ru.murad.myvpn.exception.PaymentOrderValidationException;
import ru.murad.myvpn.model.PaymentProviderType;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentPropertiesTest {

    private static final URI RETURN_URL =
            URI.create("https://example.invalid/payment-return");
    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(PropertiesConfiguration.class)
                    .withBean(PaymentEnvironmentGuard.class)
                    .withPropertyValues(
                            "payment.pending-ttl=1h",
                            "payment.return-url=https://example.invalid/return");

    @Test
    void fakeMustRequireExplicitAllowFlagRegardlessOfProfile() {
        PaymentProperties disabled = properties(
                PaymentProviderType.FAKE, Duration.ofHours(1), false);
        PaymentProperties enabled = properties(
                PaymentProviderType.FAKE, Duration.ofHours(1), true);

        assertThatThrownBy(() -> new PaymentEnvironmentGuard(disabled))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new PaymentEnvironmentGuard(enabled))
                .doesNotThrowAnyException();
    }

    @Test
    void guardMustNotBlockFutureNonFakeProvider() {
        assertThatCode(() -> new PaymentEnvironmentGuard(properties(
                PaymentProviderType.YOOKASSA, Duration.ofHours(1), false)))
                .doesNotThrowAnyException();
    }

    @Test
    void ttlMustBeStrictlyPositiveAndAtMostTwentyFourHours() {
        for (Duration invalid : new Duration[]{
                null, Duration.ZERO, Duration.ofNanos(-1),
                Duration.ofHours(24).plusNanos(1)}) {
            assertThatThrownBy(() -> properties(
                    PaymentProviderType.FAKE, invalid, true))
                    .isInstanceOf(PaymentOrderValidationException.class);
        }
        assertThatCode(() -> properties(
                PaymentProviderType.FAKE, Duration.ofNanos(1), true))
                .doesNotThrowAnyException();
        assertThatCode(() -> properties(
                PaymentProviderType.FAKE, Duration.ofHours(24), true))
                .doesNotThrowAnyException();
    }

    @Test
    void unknownProviderNameMustNotMapToFallback() {
        assertThatThrownBy(() -> PaymentProviderType.valueOf("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void springContextMustRequireExplicitFakeFlagForEveryProfile() {
        for (String profile : new String[]{"local", "test"}) {
            contextRunner.withPropertyValues(
                            "spring.profiles.active=" + profile,
                            "payment.provider=fake",
                            "payment.allow-fake=false")
                    .run(context -> assertThat(context).hasFailed());
            contextRunner.withPropertyValues(
                            "spring.profiles.active=" + profile,
                            "payment.provider=fake")
                    .run(context -> assertThat(context).hasFailed());
        }
        contextRunner.withPropertyValues(
                        "payment.provider=fake",
                        "payment.allow-fake=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void springContextMustFailForInvalidBindingAndTtlButNotGuardYookassa() {
        contextRunner.withPropertyValues(
                        "payment.provider=UNKNOWN",
                        "payment.allow-fake=false")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues(
                        "payment.provider=fake",
                        "payment.allow-fake=true",
                        "payment.pending-ttl=25h")
                .run(context -> assertThat(context).hasFailed());
        contextRunner.withPropertyValues(
                        "payment.provider=yookassa",
                        "payment.allow-fake=false")
                .run(context -> assertThat(context).hasNotFailed());
    }

    private PaymentProperties properties(
            PaymentProviderType provider,
            Duration ttl,
            boolean allowFake
    ) {
        return new PaymentProperties(provider, ttl, RETURN_URL, allowFake);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PaymentProperties.class)
    static class PropertiesConfiguration {
    }
}
