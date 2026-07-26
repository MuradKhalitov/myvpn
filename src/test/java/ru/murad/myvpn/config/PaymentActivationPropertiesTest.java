package ru.murad.myvpn.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.time.Duration;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentActivationPropertiesTest {
    @Test
    void activationBoundsAreValidated() {
        assertThatThrownBy(() -> new PaymentProperties.Activation(false, Duration.ZERO, 1,
                Duration.ofMinutes(1), Duration.ofSeconds(1), 1)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new PaymentProperties.Activation(false, Duration.ofSeconds(1), 101,
                Duration.ofMinutes(1), Duration.ofSeconds(1), 1)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new PaymentProperties.Activation(false, Duration.ofSeconds(1), 1,
                Duration.ofMinutes(31), Duration.ofSeconds(1), 1)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new PaymentProperties.Activation(false, Duration.ofSeconds(1), 1,
                Duration.ofMinutes(1), Duration.ofSeconds(1), 101)).isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @MethodSource("validActivations")
    void validBoundaryValuesAreAccepted(PaymentProperties.Activation activation) {
        assertThat(activation).isNotNull();
    }

    @ParameterizedTest
    @MethodSource("invalidActivations")
    void invalidBoundaryValuesAreRejected(Duration fixedDelay, int batchSize, Duration lease,
                                           Duration retry, int maxAttempts) {
        assertThatThrownBy(() -> new PaymentProperties(
                ru.murad.myvpn.model.PaymentProviderType.FAKE, Duration.ofHours(1),
                java.net.URI.create("https://example.invalid"), true,
                new PaymentProperties.Verification(Duration.ofSeconds(1), Duration.ZERO),
                new PaymentProperties.Activation(false, fixedDelay, batchSize, lease, retry, maxAttempts)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void nullActivationUsesSafeDefaults() {
        PaymentProperties properties = new PaymentProperties(
                ru.murad.myvpn.model.PaymentProviderType.FAKE, Duration.ofHours(1),
                java.net.URI.create("https://example.invalid"), true);
        assertThat(properties.activation().batchSize()).isEqualTo(20);
        assertThat(properties.activation().maxAttempts()).isEqualTo(5);
    }

    private static Stream<Arguments> validActivations() {
        return Stream.of(
                Arguments.of(new PaymentProperties.Activation(false, Duration.ofNanos(1), 1,
                        Duration.ofNanos(1), Duration.ofNanos(1), 1)),
                Arguments.of(new PaymentProperties.Activation(true, Duration.ofHours(1), 100,
                        Duration.ofMinutes(30), Duration.ofHours(24), 100)));
    }

    private static Stream<Arguments> invalidActivations() {
        return Stream.of(
                Arguments.of(Duration.ZERO, 1, Duration.ofSeconds(1), Duration.ofSeconds(1), 1),
                Arguments.of(Duration.ofSeconds(1), 0, Duration.ofSeconds(1), Duration.ofSeconds(1), 1),
                Arguments.of(Duration.ofSeconds(1), 1, Duration.ZERO, Duration.ofSeconds(1), 1),
                Arguments.of(Duration.ofSeconds(1), 1, Duration.ofSeconds(1), Duration.ZERO, 1),
                Arguments.of(Duration.ofSeconds(1), 1, Duration.ofSeconds(1), Duration.ofSeconds(1), 0),
                Arguments.of(Duration.ofHours(1).plusNanos(1), 1, Duration.ofSeconds(1), Duration.ofSeconds(1), 1));
    }
}
