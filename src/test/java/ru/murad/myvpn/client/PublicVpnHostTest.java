package ru.murad.myvpn.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.murad.myvpn.exception.ThreeXUiException;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicVpnHostTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "vpn.example.com",
            "subdomain.vpn.example.com",
            "localhost",
            "203.0.113.10",
            "127.0.0.1",
            "10.0.0.1",
            "192.168.1.1",
            "2001:db8::1",
            "[2001:db8::1]",
            "::1",
            "[::1]"
    })
    void shouldAcceptSyntacticallyValidHosts(String value) {
        PublicVpnHost host = new PublicVpnHost(value);

        assertThat(host.value()).doesNotContain("[", "]");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[not-ipv6]",
            "[vpn.example.com]",
            "[]",
            "[2001:db8::1",
            "2001:db8::1]",
            "[2001:db8::1]:443",
            "fe80::1%eth0",
            "fe80::1%25eth0",
            "[fe80::1%eth0]",
            "[fe80::1%25eth0]",
            "0.0.0.0",
            "::",
            "[::]",
            "-vpn.example.com",
            "vpn-.example.com",
            "vpn..example.com",
            ".",
            ".."
    })
    void shouldRejectInvalidHostsWithoutEchoingInput(String value) {
        assertThatThrownBy(() -> new PublicVpnHost(value))
                .isInstanceOf(ThreeXUiException.class)
                .hasMessage("Invalid public VPN host")
                .hasMessageNotContaining(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2001:db8::1", "[2001:db8::1]"})
    void shouldNormalizeBracketedAndUnbracketedIpv6(String value) {
        assertThat(new PublicVpnHost(value).value()).isEqualTo("2001:db8::1");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "001.002.003.004",
            "01.2.3.4",
            "1.02.3.4",
            "1.2.003.4",
            "1.2.3.004",
            "00.0.0.1",
            "1.2.3",
            "1.2.3.4.5",
            "256.1.1.1",
            "1.256.1.1",
            "1.1.256.1",
            "1.1.1.256",
            "-1.2.3.4",
            "+1.2.3.4",
            "1..2.3",
            "1.2..3",
            "1.2.3.",
            ".1.2.3"
    })
    void shouldRejectMalformedIpv4(String value) {
        assertRejectedWithoutEcho(value);
    }

    @ParameterizedTest
    @MethodSource("invalidWhitespaceAndControlHosts")
    void shouldRejectWhitespaceAndControlCharacters(String value) {
        assertRejectedWithoutEcho(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "vpn_example.com",
            "example.com.",
            "éxample.com",
            "-vpn.example.com",
            "vpn-.example.com",
            "vpn..example.com"
    })
    void shouldRejectInvalidHostnameForms(String value) {
        assertRejectedWithoutEcho(value);
    }

    @Test
    void shouldAcceptPunycodeHostname() {
        assertThat(new PublicVpnHost("xn--e1afmkfd.xn--p1ai").value())
                .isEqualTo("xn--e1afmkfd.xn--p1ai");
    }

    @Test
    void shouldEnforceHostnameAndLabelLengthLimits() {
        String label63 = "a".repeat(63);
        String label64 = "a".repeat(64);
        String hostname253 = String.join(".",
                label63, label63, label63, "a".repeat(61));
        String hostname254 = String.join(".",
                label63, label63, label63, "a".repeat(62));

        assertThat(new PublicVpnHost(label63 + ".example").value())
                .isEqualTo(label63 + ".example");
        assertThat(new PublicVpnHost(hostname253).value())
                .isEqualTo(hostname253);
        assertRejectedWithoutEcho(label64 + ".example");
        assertRejectedWithoutEcho(hostname254);
    }

    private static Stream<String> invalidWhitespaceAndControlHosts() {
        return Stream.of(
                "vpn.example.com\t",
                "vpn.example.com\n",
                "vpn.example.com\r",
                "vpn" + '\0' + ".example.com",
                "vpn example.com");
    }

    private void assertRejectedWithoutEcho(String value) {
        assertThatThrownBy(() -> new PublicVpnHost(value))
                .isInstanceOf(ThreeXUiException.class)
                .hasMessage("Invalid public VPN host")
                .hasMessageNotContaining(value);
    }
}
