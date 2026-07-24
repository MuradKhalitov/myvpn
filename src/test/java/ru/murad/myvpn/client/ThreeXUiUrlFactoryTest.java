package ru.murad.myvpn.client;

import org.junit.jupiter.api.Test;
import ru.murad.myvpn.config.ThreeXUiProperties;
import ru.murad.myvpn.exception.ThreeXUiException;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThreeXUiUrlFactoryTest {

    @Test
    void shouldNormalizeWebBasePathWithoutDuplicatingApiSegments() {
        ThreeXUiUrlFactory factory = new ThreeXUiUrlFactory(
                properties(URI.create("https://test.invalid/"), "//hidden//path//"));

        assertThat(factory.login().toString())
                .isEqualTo("https://test.invalid/hidden/path/login");
        assertThat(factory.inbound(42).toString())
                .isEqualTo("https://test.invalid/hidden/path/panel/api/inbounds/get/42");
    }

    @Test
    void shouldRejectPanelPathInBaseUrlOrWebBasePath() {
        assertThatThrownBy(() -> new ThreeXUiUrlFactory(
                properties(URI.create("https://test.invalid/panel"), "/hidden")))
                .isInstanceOf(ThreeXUiException.class);
        assertThatThrownBy(() -> new ThreeXUiUrlFactory(
                properties(URI.create("https://test.invalid"), "/panel/hidden")))
                .isInstanceOf(ThreeXUiException.class);
    }

    @Test
    void shouldRejectHttpUnlessExplicitlyAllowedForTests() {
        ThreeXUiProperties http = properties(
                URI.create("http://test.invalid"), "/sensitive-path");

        assertThatThrownBy(() -> new ThreeXUiUrlFactory(http))
                .isInstanceOf(ThreeXUiException.class)
                .hasMessage("3x-ui base URL must use HTTPS")
                .hasMessageNotContaining("sensitive-path");
        assertThat(new ThreeXUiUrlFactory(http, true).login().getScheme())
                .isEqualTo("http");
    }

    private ThreeXUiProperties properties(URI baseUrl, String webPath) {
        return new ThreeXUiProperties(
                baseUrl, webPath, "user", "password", 42,
                "vpn.example.test", null,
                Duration.ofSeconds(5), Duration.ofSeconds(10), 3,
                8,
                Duration.ofSeconds(1), Duration.ofSeconds(8));
    }
}
