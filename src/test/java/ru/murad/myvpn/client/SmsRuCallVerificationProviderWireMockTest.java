package ru.murad.myvpn.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import ru.murad.myvpn.config.SmsRuProperties;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmsRuCallVerificationProviderWireMockTest {
    private WireMockServer server;
    private SmsRuCallVerificationProvider provider;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(0);
        server.start();
        provider = new SmsRuCallVerificationProvider(WebClient.builder().baseUrl(server.baseUrl()).build(),
                new SmsRuProperties(true, "test-api-id", URI.create(server.baseUrl()), Duration.ofSeconds(1), Duration.ofSeconds(1)),
                new ObjectMapper(), Clock.fixed(Instant.parse("2026-09-04T00:00:00Z"), ZoneOffset.UTC));
    }

    @AfterEach void tearDown() { server.stop(); }

    @Test
    void startMapsOfficialSuccessResponseAndSendsOnlyBackendApiId() {
        server.stubFor(post(urlEqualTo("/callcheck/add")).willReturn(json("""
                {"status":"OK","status_code":100,"check_id":"check-1","call_phone":"7800","call_phone_pretty":"+7 800"}
                """)));

        PhoneVerificationStart result = provider.start("+79991234567");

        assertThat(result.externalCheckId()).isEqualTo("check-1");
        assertThat(result.expiresAt()).isEqualTo(Instant.parse("2026-09-04T00:05:00Z"));
        server.verify(postRequestedFor(urlEqualTo("/callcheck/add"))
                .withRequestBody(containing("api_id=test-api-id"))
                .withRequestBody(containing("phone=%2B79991234567")));
    }

    @Test
    void statusMapsPendingVerifiedAndExpired() {
        assertStatus("400", PhoneVerificationState.PENDING);
        assertStatus("401", PhoneVerificationState.VERIFIED);
        assertStatus("402", PhoneVerificationState.EXPIRED);
    }

    @Test
    void malformedAndProviderErrorResponsesFailClosed() {
        server.stubFor(post(urlEqualTo("/callcheck/add")).willReturn(json("{\"status\":\"OK\",\"status_code\":100}")));
        assertThatThrownBy(() -> provider.start("+79991234567")).isInstanceOf(IllegalStateException.class);
        server.resetAll();
        server.stubFor(post(urlEqualTo("/callcheck/status")).willReturn(json("{\"status\":\"ERROR\",\"status_code\":201}")));
        assertThatThrownBy(() -> provider.getStatus("check-1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void httpFailureFailsClosed() {
        server.stubFor(post(urlEqualTo("/callcheck/status")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> provider.getStatus("check-1")).isInstanceOf(IllegalStateException.class);
    }

    private void assertStatus(String status, PhoneVerificationState expected) {
        server.resetAll();
        server.stubFor(post(urlEqualTo("/callcheck/status")).willReturn(json("""
                {"status":"OK","status_code":100,"check_status":"%s"}
                """.formatted(status))));
        assertThat(provider.getStatus("check-1")).isEqualTo(expected);
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withHeader("Content-Type", "application/json").withBody(body);
    }
}
