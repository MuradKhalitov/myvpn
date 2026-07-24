package ru.murad.myvpn.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.murad.myvpn.exception.ThreeXUiException;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThreeXUiVlessConfigurationFactoryTest {

    private static final String CLIENT_ID =
            "30000000-0000-0000-0000-000000000001";
    private final ThreeXUiVlessConfigurationFactory factory =
            new ThreeXUiVlessConfigurationFactory();

    @Test
    void shouldCreateRealityTcpUriWithRequiredAndOptionalParameters() {
        String uri = factory.create(data());

        assertThat(uri)
                .startsWith("vless://" + CLIENT_ID + "@vpn.example.test:443?")
                .contains("type=tcp", "security=reality", "encryption=none",
                        "sni=server.example", "fp=chrome", "pbk=public-key",
                        "sid=abcd", "flow=xtls-rprx-vision", "spx=%2Fpath")
                .endsWith("#MyVPN");
    }

    @Test
    void shouldOmitEmptyOptionalValues() {
        VlessConfigurationData source = data();
        String uri = factory.create(copy(source, "", ""));

        assertThat(uri).doesNotContain("flow=").doesNotContain("spx=");
    }

    @Test
    void shouldEncodeQueryValuesAndFragmentOnce() {
        VlessConfigurationData source = data();
        VlessConfigurationData encoded = new VlessConfigurationData(
                source.clientId(), source.publicHost(), source.publicPort(),
                source.network(), source.security(), source.encryption(), "",
                "server name", "fire fox", "public+/key", "ab cd",
                "/path?x=1", "VPN Finland #1");

        String uri = factory.create(encoded);

        assertThat(uri)
                .contains("sni=server%20name", "fp=fire%20fox",
                        "pbk=public%2B%2Fkey", "sid=ab%20cd",
                        "spx=%2Fpath%3Fx%3D1")
                .endsWith("#VPN%20Finland%20%231")
                .doesNotContain("%252F");
    }

    @Test
    void shouldFormatIpv6HostWithBrackets() {
        VlessConfigurationData source = data();
        VlessConfigurationData ipv6 = new VlessConfigurationData(
                source.clientId(), "2001:db8::1", source.publicPort(),
                source.network(), source.security(), source.encryption(),
                source.flow(), source.serverName(), source.fingerprint(),
                source.publicKey(), source.shortId(), source.spiderX(),
                source.displayName());

        assertThat(factory.create(ipv6))
                .startsWith("vless://" + CLIENT_ID + "@[2001:db8::1]:443?");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "vpn.example.com",
            "203.0.113.10",
            "2001:db8::1",
            "[2001:db8::1]"
    })
    void shouldBuildSemanticUriForValidHosts(String host) {
        VlessConfigurationData source = withHostAndPort(data(), host, 443);

        URI uri = URI.create(factory.create(source));

        assertThat(uri.getScheme()).isEqualTo("vless");
        assertThat(uri.getRawUserInfo()).isEqualTo(CLIENT_ID);
        assertThat(uri.getHost()).isIn(
                "vpn.example.com", "203.0.113.10", "[2001:db8::1]");
        assertThat(uri.getPort()).isEqualTo(443);
        assertThat(decodedQuery(uri)).containsEntry("type", "tcp")
                .containsEntry("security", "reality")
                .containsEntry("spx", "/path");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            " vpn.example.com",
            "vpn.example.com ",
            "https://vpn.example.com",
            "vpn.example.com/path",
            "vpn.example.com?x=1",
            "vpn.example.com#fragment",
            "vpn.example.com:443",
            "user@vpn.example.com",
            "vpn example.com",
            ""
    })
    void shouldRejectInvalidHosts(String host) {
        assertThatThrownBy(() -> factory.create(
                withHostAndPort(data(), host, 443)))
                .isInstanceOf(ThreeXUiException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 443, 65535})
    void shouldAcceptValidPorts(int port) {
        assertThat(URI.create(factory.create(
                withHostAndPort(data(), "vpn.example.test", port))).getPort())
                .isEqualTo(port);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 65536})
    void shouldRejectInvalidPorts(int port) {
        assertInvalid(withHostAndPort(data(), "vpn.example.test", port));
    }

    @Test
    void shouldEncodeUnicodePercentAndComplexSpiderPathWithoutLoss() {
        VlessConfigurationData source = data();
        VlessConfigurationData special = new VlessConfigurationData(
                source.clientId(), source.publicHost(), source.publicPort(),
                source.network(), source.security(), null, "",
                source.serverName(), source.fingerprint(), source.publicKey(),
                source.shortId(), "/path?x=1&y=2%", "Финляндия 100%");

        URI uri = URI.create(factory.create(special));

        assertThat(decodedQuery(uri))
                .doesNotContainKey("encryption")
                .doesNotContainKey("flow")
                .containsEntry("spx", "/path?x=1&y=2%");
        assertThat(decode(uri.getRawFragment())).isEqualTo("Финляндия 100%");
        assertThat(uri.toASCIIString()).doesNotContain("%2525");
    }

    @Test
    void shouldRejectMissingRequiredValues() {
        VlessConfigurationData source = data();
        assertInvalid(new VlessConfigurationData(
                source.clientId(), "", source.publicPort(), source.network(),
                source.security(), source.encryption(), source.flow(),
                source.serverName(), source.fingerprint(), source.publicKey(),
                source.shortId(), source.spiderX(), source.displayName()));
        assertInvalid(replaceReality(source, null, source.fingerprint(),
                source.publicKey(), source.shortId()));
        assertInvalid(replaceReality(source, source.serverName(),
                source.fingerprint(), null, source.shortId()));
        assertInvalid(replaceReality(source, source.serverName(),
                source.fingerprint(), source.publicKey(), null));
    }

    @Test
    void shouldRejectUnknownNetworkOrSecurity() {
        VlessConfigurationData source = data();
        assertUnsupported(new VlessConfigurationData(
                source.clientId(), source.publicHost(), source.publicPort(),
                "grpc", source.security(), source.encryption(), source.flow(),
                source.serverName(), source.fingerprint(), source.publicKey(),
                source.shortId(), source.spiderX(), source.displayName()));
        assertUnsupported(new VlessConfigurationData(
                source.clientId(), source.publicHost(), source.publicPort(),
                source.network(), "tls", source.encryption(), source.flow(),
                source.serverName(), source.fingerprint(), source.publicKey(),
                source.shortId(), source.spiderX(), source.displayName()));
    }

    @Test
    void safeModelCannotContainPrivateKeyAndToStringCannotExposeUri() {
        assertThat(Arrays.stream(VlessConfigurationData.class
                        .getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain("privateKey", "settings", "streamSettings");
        assertThat(data().toString())
                .doesNotContain(CLIENT_ID, "public-key", "abcd", "vless://");
        assertThat(new ProvisionedVpnAccess(
                "3X_UI", CLIENT_ID, factory.create(data())).toString())
                .doesNotContain(CLIENT_ID, "public-key", "abcd", "vless://");
    }

    @Test
    void uriMustNotContainNullOrOptionalRepresentation() {
        assertThat(factory.create(copy(data(), null, null)))
                .doesNotContain("null", "Optional[");
    }

    private VlessConfigurationData data() {
        return new VlessConfigurationData(
                CLIENT_ID, "vpn.example.test", 443, "tcp", "reality",
                "none", "xtls-rprx-vision", "server.example", "chrome",
                "public-key", "abcd", "/path", "MyVPN");
    }

    private VlessConfigurationData copy(
            VlessConfigurationData source,
            String flow,
            String spiderX
    ) {
        return new VlessConfigurationData(
                source.clientId(), source.publicHost(), source.publicPort(),
                source.network(), source.security(), source.encryption(), flow,
                source.serverName(), source.fingerprint(), source.publicKey(),
                source.shortId(), spiderX, source.displayName());
    }

    private VlessConfigurationData replaceReality(
            VlessConfigurationData source,
            String serverName,
            String fingerprint,
            String publicKey,
            String shortId
    ) {
        return new VlessConfigurationData(
                source.clientId(), source.publicHost(), source.publicPort(),
                source.network(), source.security(), source.encryption(),
                source.flow(), serverName, fingerprint, publicKey, shortId,
                source.spiderX(), source.displayName());
    }

    private VlessConfigurationData withHostAndPort(
            VlessConfigurationData source,
            String host,
            int port
    ) {
        return new VlessConfigurationData(
                source.clientId(), host, port, source.network(),
                source.security(), source.encryption(), source.flow(),
                source.serverName(), source.fingerprint(), source.publicKey(),
                source.shortId(), source.spiderX(), source.displayName());
    }

    private java.util.Map<String, String> decodedQuery(URI uri) {
        java.util.Map<String, String> result = new java.util.LinkedHashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            String[] values = pair.split("=", 2);
            result.put(decode(values[0]), decode(values[1]));
        }
        return result;
    }

    private String decode(String value) {
        java.io.ByteArrayOutputStream output =
                new java.io.ByteArrayOutputStream();
        for (int index = 0; index < value.length();) {
            if (value.charAt(index) == '%') {
                output.write(Integer.parseInt(
                        value.substring(index + 1, index + 3), 16));
                index += 3;
            } else {
                byte[] bytes = String.valueOf(value.charAt(index))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                output.writeBytes(bytes);
                index++;
            }
        }
        return output.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private void assertInvalid(VlessConfigurationData value) {
        assertThatThrownBy(() -> factory.create(value))
                .isInstanceOf(ThreeXUiException.class)
                .hasMessage("Incomplete VLESS configuration");
    }

    private void assertUnsupported(VlessConfigurationData value) {
        assertThatThrownBy(() -> factory.create(value))
                .isInstanceOf(ThreeXUiException.class)
                .hasMessage(
                        "Unsupported VLESS transport or security configuration");
    }
}
