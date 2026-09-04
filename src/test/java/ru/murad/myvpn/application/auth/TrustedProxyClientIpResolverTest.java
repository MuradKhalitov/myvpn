package ru.murad.myvpn.application.auth;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TrustedProxyClientIpResolverTest {
    private final TrustedProxyClientIpResolver resolver = new TrustedProxyClientIpResolver();

    @Test
    void differentTrustedProxyClientIpsProduceDifferentNormalizedBuckets() {
        assertThat(resolve("127.0.0.1", "198.51.100.10")).isEqualTo("198.51.100.10");
        assertThat(resolve("127.0.0.1", "198.51.100.11")).isEqualTo("198.51.100.11");
        assertThat(resolve("::1", "2001:DB8::1")).isEqualTo("2001:db8:0:0:0:0:0:1");
    }

    @Test
    void spoofedForwardedHeadersFromPublicPeerCannotReplaceSocketIdentity() {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Real-IP", "198.51.100.44");
        headers.add("X-Forwarded-For", "198.51.100.55");
        when(request.getHeaders()).thenReturn(headers);
        when(request.getRemoteAddress()).thenReturn(new InetSocketAddress("203.0.113.9", 1234));

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.9");
    }

    private String resolve(String peer, String realIp) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders(); headers.add("X-Real-IP", realIp);
        when(request.getHeaders()).thenReturn(headers);
        when(request.getRemoteAddress()).thenReturn(new InetSocketAddress(peer, 1234));
        return resolver.resolve(request);
    }
}
