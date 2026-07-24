package ru.murad.myvpn.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import ru.murad.myvpn.client.threexui.ThreeXUiApiResponse;
import ru.murad.myvpn.client.threexui.ThreeXUiClientRequest;
import ru.murad.myvpn.client.threexui.ThreeXUiInboundResponse;
import ru.murad.myvpn.client.threexui.ThreeXUiInboundSettings;
import ru.murad.myvpn.client.threexui.ThreeXUiVlessClient;
import ru.murad.myvpn.exception.ThreeXUiException;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ThreeXUiSensitiveDtoTest {

    private static final List<String> MARKERS = List.of(
            "PRIVATE_KEY_SECRET_MARKER",
            "PUBLIC_KEY_MARKER",
            "CLIENT_UUID_MARKER",
            "SHORT_ID_MARKER",
            "SERVER_NAME_MARKER",
            "RAW_BODY_MARKER");

    @Test
    void rawDtoToStringsMustRedactSensitivePayloads() {
        String payload = String.join(" ", MARKERS);
        ThreeXUiInboundResponse inbound = new ThreeXUiInboundResponse(
                42, 443, "vless", payload, payload);
        ThreeXUiInboundClient.RawResponse raw =
                new ThreeXUiInboundClient.RawResponse(
                        HttpStatus.OK, payload, MediaType.APPLICATION_JSON,
                        payload);
        ThreeXUiApiResponse<ThreeXUiInboundResponse> envelope =
                new ThreeXUiApiResponse<>(true, payload, inbound);
        ThreeXUiClientRequest request =
                new ThreeXUiClientRequest(42, payload);
        ThreeXUiVlessClient client = ThreeXUiVlessClient.create(
                "CLIENT_UUID_MARKER", "RAW_BODY_MARKER", 1L);
        ThreeXUiInboundSettings settings =
                new ThreeXUiInboundSettings(List.of(client));

        assertRedacted(inbound.toString());
        assertRedacted(raw.toString());
        assertRedacted(envelope.toString());
        assertRedacted(request.toString());
        assertRedacted(client.toString());
        assertRedacted(settings.toString());
    }

    @Test
    void parsingFailureMustNotExposeRawJsonInThrowable() {
        String payload = "{\"clients\":[\"" + String.join("\",\"", MARKERS);
        ThreeXUiConfigurationMapper mapper = new ThreeXUiConfigurationMapper(
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new ru.murad.myvpn.config.ThreeXUiProperties(
                        java.net.URI.create("https://panel.invalid"), "/hidden",
                        "user", "password", 42, "vpn.example.test", null,
                        java.time.Duration.ofSeconds(1),
                        java.time.Duration.ofSeconds(1),
                        3, 8, java.time.Duration.ZERO,
                        java.time.Duration.ZERO),
                () -> "/fixedSpiderPath");

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> mapper.map(new ThreeXUiInboundResponse(
                        42, 443, "vless", payload, payload),
                        "CLIENT_UUID_MARKER"));

        assertThat(thrown).isInstanceOf(ThreeXUiException.class);
        for (Throwable current = thrown;
             current != null;
             current = current.getCause()) {
            assertRedacted(current.getMessage());
            assertRedacted(current.toString());
        }
        StringWriter stackTrace = new StringWriter();
        thrown.printStackTrace(new PrintWriter(stackTrace));
        assertRedacted(stackTrace.toString());
    }

    private void assertRedacted(String value) {
        for (String marker : MARKERS) {
            assertThat(value).doesNotContain(marker);
        }
    }
}
