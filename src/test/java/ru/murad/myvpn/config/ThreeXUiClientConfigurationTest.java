package ru.murad.myvpn.config;

import org.junit.jupiter.api.Test;
import ru.murad.myvpn.exception.ThreeXUiException;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThreeXUiClientConfigurationTest {

    private final ThreeXUiClientConfiguration configuration =
            new ThreeXUiClientConfiguration();

    @Test
    void shouldRejectMissingPublicHostWithoutExposingConfiguration() {
        ThreeXUiProperties properties = properties("");

        assertThatThrownBy(() -> configuration.threeXUiWebClient(properties))
                .isInstanceOf(ThreeXUiException.class)
                .hasMessage("Invalid 3x-ui configuration")
                .hasMessageNotContaining("hidden-path")
                .hasMessageNotContaining("user")
                .hasMessageNotContaining("password");
    }

    @Test
    void shouldAcceptExplicitPublicHost() {
        configuration.threeXUiWebClient(properties("vpn.example.test"));
    }

    @ParameterizedTest
    @MethodSource("invalidProperties")
    void shouldRejectInvalidTimeoutAndRetryPropertyValues(ThreeXUiProperties properties) {
        assertThatThrownBy(() -> configuration.threeXUiWebClient(properties))
                .isInstanceOf(ThreeXUiException.class)
                .hasMessage("Invalid 3x-ui configuration");
    }

    @Test
    void propertiesStringRepresentationsMustRedactSensitiveValues() {
        ThreeXUiProperties properties = new ThreeXUiProperties(
                URI.create("https://BASE_URL_SECRET.invalid"),
                "/WEB_PATH_SECRET", "USERNAME_SECRET", "PASSWORD_SECRET",
                42, "PUBLIC_HOST_SECRET.invalid", 443,
                Duration.ofSeconds(5), Duration.ofSeconds(10),
                3, 8, Duration.ZERO, Duration.ZERO);

        for (String representation : new String[]{
                properties.toString(),
                String.valueOf(properties),
                Objects.toString(properties),
                String.format("%s", properties)}) {
            assertThat(representation)
                    .contains("baseUrlRedacted=true")
                    .contains("webBasePathRedacted=true")
                    .contains("usernameRedacted=true")
                    .contains("passwordRedacted=true")
                    .contains("publicHostRedacted=true")
                    .doesNotContain(
                            "BASE_URL_SECRET",
                            "WEB_PATH_SECRET",
                            "USERNAME_SECRET",
                            "PASSWORD_SECRET",
                            "PUBLIC_HOST_SECRET");
        }
    }

    private ThreeXUiProperties properties(String publicHost) {
        return new ThreeXUiProperties(
                URI.create("https://panel.invalid"), "/hidden-path",
                "user", "password", 42, publicHost, null,
                Duration.ofSeconds(5), Duration.ofSeconds(10),
                3, 8, Duration.ZERO, Duration.ZERO);
    }

    private static Stream<ThreeXUiProperties> invalidProperties() {
        ThreeXUiProperties valid = new ThreeXUiProperties(
                URI.create("https://panel.invalid"), "/hidden-path", "user", "password",
                42, "vpn.example.test", null, Duration.ofSeconds(5), Duration.ofSeconds(10),
                3, 8, Duration.ofSeconds(1), Duration.ofSeconds(2));
        return Stream.of(
                new ThreeXUiProperties(valid.baseUrl(), valid.webBasePath(), valid.username(), valid.password(), valid.inboundId(), valid.publicHost(), valid.publicPortOverride(), Duration.ZERO, valid.readTimeout(), valid.maxMutationAttempts(), valid.maxRequestsPerOperation(), valid.retryInitialDelay(), valid.retryMaxDelay()),
                new ThreeXUiProperties(valid.baseUrl(), valid.webBasePath(), valid.username(), valid.password(), valid.inboundId(), valid.publicHost(), valid.publicPortOverride(), valid.connectTimeout(), Duration.ZERO, valid.maxMutationAttempts(), valid.maxRequestsPerOperation(), valid.retryInitialDelay(), valid.retryMaxDelay()),
                new ThreeXUiProperties(valid.baseUrl(), valid.webBasePath(), valid.username(), valid.password(), valid.inboundId(), valid.publicHost(), valid.publicPortOverride(), valid.connectTimeout(), valid.readTimeout(), valid.maxMutationAttempts(), valid.maxRequestsPerOperation(), Duration.ofSeconds(2), Duration.ofSeconds(1)),
                new ThreeXUiProperties(valid.baseUrl(), valid.webBasePath(), valid.username(), valid.password(), 0, valid.publicHost(), valid.publicPortOverride(), valid.connectTimeout(), valid.readTimeout(), valid.maxMutationAttempts(), valid.maxRequestsPerOperation(), valid.retryInitialDelay(), valid.retryMaxDelay()),
                new ThreeXUiProperties(valid.baseUrl(), valid.webBasePath(), valid.username(), valid.password(), valid.inboundId(), valid.publicHost(), 0, valid.connectTimeout(), valid.readTimeout(), valid.maxMutationAttempts(), valid.maxRequestsPerOperation(), valid.retryInitialDelay(), valid.retryMaxDelay()));
    }
}
