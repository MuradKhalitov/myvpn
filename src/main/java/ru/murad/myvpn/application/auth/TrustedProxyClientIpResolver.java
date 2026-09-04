package ru.murad.myvpn.application.auth;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/** Accepts X-Real-IP only from the local nginx reverse proxy. */
@Component
public class TrustedProxyClientIpResolver {
    public String resolve(ServerHttpRequest request) {
        InetSocketAddress peer = request.getRemoteAddress();
        if (peer == null || peer.getAddress() == null) return null;
        InetAddress peerAddress = peer.getAddress();
        String candidate = peerAddress.isLoopbackAddress()
                ? request.getHeaders().getFirst("X-Real-IP") : null;
        InetAddress result = parseLiteral(candidate);
        return result == null ? normalize(peerAddress) : normalize(result);
    }

    private InetAddress parseLiteral(String value) {
        if (value == null || value.isBlank() || !value.matches("[0-9a-fA-F:.]+")) return null;
        try {
            InetAddress address = InetAddress.getByName(value);
            return address.isAnyLocalAddress() ? null : address;
        } catch (Exception ignored) { return null; }
    }
    private String normalize(InetAddress address) { return address.getHostAddress().toLowerCase(java.util.Locale.ROOT); }
}
