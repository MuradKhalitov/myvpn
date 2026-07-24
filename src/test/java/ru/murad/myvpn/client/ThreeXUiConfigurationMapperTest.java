package ru.murad.myvpn.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.murad.myvpn.client.threexui.ThreeXUiInboundResponse;
import ru.murad.myvpn.config.ThreeXUiProperties;
import ru.murad.myvpn.exception.ThreeXUiException;
import ru.murad.myvpn.exception.ThreeXUiNotFoundException;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThreeXUiConfigurationMapperTest {

    private static final String TARGET =
            "30000000-0000-0000-0000-000000000002";
    private static final String PRIVATE_MARKER = "PRIVATE_KEY_MARKER";
    private ThreeXUiConfigurationMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ThreeXUiConfigurationMapper(
                new ObjectMapper(),
                new ThreeXUiProperties(
                        URI.create("https://panel.invalid"), "/hidden",
                        "user", "password", 42, "vpn.example.test", null,
                        Duration.ofSeconds(5), Duration.ofSeconds(10),
                        3, 8, Duration.ZERO, Duration.ZERO),
                () -> "/fixedSpiderPath");
    }

    @Test
    void shouldMapTargetRealityTcpClientAndChooseFirstNonBlankValues() {
        VlessConfigurationData result = mapper.map(inbound(settings(), stream()), TARGET);

        assertThat(result.clientId()).isEqualTo(TARGET);
        assertThat(result.publicHost()).isEqualTo("vpn.example.test");
        assertThat(result.publicPort()).isEqualTo(443);
        assertThat(result.network()).isEqualTo("tcp");
        assertThat(result.security()).isEqualTo("reality");
        assertThat(result.encryption()).isEqualTo("none");
        assertThat(result.flow()).isEqualTo("xtls-rprx-vision");
        assertThat(result.serverName()).isEqualTo("server.example");
        assertThat(result.shortId()).isEqualTo("abcd");
        assertThat(result.fingerprint()).isEqualTo("chrome");
        assertThat(result.publicKey()).isEqualTo("public-key");
        assertThat(result.spiderX()).isEqualTo("/fixedSpiderPath");
        assertThat(result.toString()).doesNotContain(PRIVATE_MARKER);
    }

    @Test
    void shouldSelectTargetInsteadOfFirstServiceClient() {
        assertThat(mapper.map(inbound(settings(), stream()), TARGET).clientId())
                .isEqualTo(TARGET)
                .isNotEqualTo("30000000-0000-0000-0000-000000000001");
    }

    @Test
    void privateKeyMustNotBeRepresentableInMappedModel() {
        VlessConfigurationData result = mapper.map(inbound(settings(), stream()), TARGET);

        assertThat(result.getClass().getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("privateKey");
        assertThat(java.util.Arrays.toString(result.getClass()
                        .getRecordComponents()))
                .doesNotContain(PRIVATE_MARKER);
        for (var component : result.getClass().getRecordComponents()) {
            try {
                assertThat(component.getAccessor().invoke(result))
                        .as(component.getName())
                        .isNotEqualTo(PRIVATE_MARKER);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError(exception);
            }
        }
        assertThat(result.spiderX()).isEqualTo("/fixedSpiderPath")
                .doesNotContain("SERVER_SPIDER_MARKER");
    }

    @Test
    void shouldRejectMissingClient() {
        assertThatThrownBy(() -> mapper.map(
                inbound(settings(), stream()),
                "30000000-0000-0000-0000-000000000099"))
                .isInstanceOf(ThreeXUiNotFoundException.class);
    }

    @Test
    void shouldSanitizeMalformedSettingsAndStreamSettings() {
        assertSanitized(inbound("{\"clients\":[", stream()));
        assertSanitized(inbound(settings(), "{\"realitySettings\":"));
    }

    @Test
    void shouldRejectNonVlessProtocol() {
        assertThatThrownBy(() -> mapper.map(new ThreeXUiInboundResponse(
                        42, 443, "trojan", settings(), stream()), TARGET))
                .isInstanceOf(ThreeXUiException.class)
                .hasMessage(
                        "Unsupported VLESS transport or security configuration");
    }

    @Test
    void shouldRejectUnsupportedStreamBeforeReadingRealitySettings() {
        for (String stream : java.util.List.of(
                "{\"network\":\"grpc\",\"security\":\"reality\"}",
                "{\"network\":\"ws\",\"security\":\"reality\"}",
                "{\"network\":\"tcp\",\"security\":\"tls\"}",
                "{\"network\":\"tcp\",\"security\":\"none\"}")) {
            assertThatThrownBy(() -> mapper.map(
                    inbound(settings(), stream), TARGET))
                    .isInstanceOf(ThreeXUiException.class)
                    .hasMessage(
                            "Unsupported VLESS transport or security configuration");
        }
    }

    @Test
    void shouldRejectEmptyServerNamesAndShortIds() {
        String noNames = stream().replace(
                "\"serverNames\":[\"\",\"server.example\",\"unused.example\"]",
                "\"serverNames\":[]");
        String noShortIds = stream().replace(
                "\"shortIds\":[\"\",\"abcd\",\"unused\"]",
                "\"shortIds\":[]");

        assertThatThrownBy(() -> mapper.map(
                inbound(settings(), noNames), TARGET))
                .isInstanceOf(ThreeXUiException.class)
                .hasMessage("Incomplete VLESS configuration");
        assertThatThrownBy(() -> mapper.map(
                inbound(settings(), noShortIds), TARGET))
                .isInstanceOf(ThreeXUiException.class)
                .hasMessage("Incomplete VLESS configuration");
    }

    @Test
    void shouldPreferPublicPortOverrideAndOtherwiseUseInboundPort() {
        assertThat(mapper.map(
                new ThreeXUiInboundResponse(
                        42, 8443, "vless", settings(), stream()),
                TARGET).publicPort()).isEqualTo(8443);
        ThreeXUiConfigurationMapper overridden =
                new ThreeXUiConfigurationMapper(
                        new ObjectMapper(),
                        new ThreeXUiProperties(
                                URI.create("https://panel.invalid"), "/hidden",
                                "user", "password", 42,
                                "vpn.example.test", 9443,
                                Duration.ofSeconds(5), Duration.ofSeconds(10),
                                3, 8, Duration.ZERO, Duration.ZERO),
                        () -> "/fixedSpiderPath");

        assertThat(overridden.map(
                new ThreeXUiInboundResponse(
                        42, 8443, "vless", settings(), stream()),
                TARGET).publicPort()).isEqualTo(9443);
    }

    private void assertSanitized(ThreeXUiInboundResponse inbound) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> mapper.map(inbound, TARGET));
        assertThat(thrown).isInstanceOf(ThreeXUiException.class)
                .hasMessage("Invalid 3x-ui configuration format")
                .hasNoCause();
        assertThat(thrown.toString())
                .doesNotContain(PRIVATE_MARKER, TARGET, "clients");
    }

    private ThreeXUiInboundResponse inbound(String settings, String stream) {
        return new ThreeXUiInboundResponse(
                42, 443, "vless", settings, stream);
    }

    private String settings() {
        return """
                {"encryption":"none","clients":[
                  {"id":"30000000-0000-0000-0000-000000000001",
                   "email":"service","flow":""},
                  {"id":"%s","email":"target","flow":"xtls-rprx-vision"}
                ]}""".formatted(TARGET);
    }

    private String stream() {
        return """
                {"network":"tcp","security":"reality","realitySettings":{
                  "serverNames":["","server.example","unused.example"],
                  "shortIds":["","abcd","unused"],
                  "privateKey":"%s",
                  "settings":{"publicKey":"public-key","fingerprint":"chrome",
                              "spiderX":"SERVER_SPIDER_MARKER"}
                }}""".formatted(PRIVATE_MARKER);
    }
}
