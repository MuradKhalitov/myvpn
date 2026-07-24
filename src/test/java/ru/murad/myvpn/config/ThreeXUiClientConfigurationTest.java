package ru.murad.myvpn.config;

import org.junit.jupiter.api.Test;
import ru.murad.myvpn.exception.ThreeXUiException;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

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
}
