package ru.murad.myvpn.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import ru.murad.myvpn.config.ThreeXUiProperties;
import ru.murad.myvpn.exception.ThreeXUiException;

import java.net.URI;
import java.util.Locale;

@Component
@ConditionalOnProperty(name = "vpn.provider.type", havingValue = "3x-ui")
public class ThreeXUiUrlFactory {

    private final String root;

    @Autowired
    public ThreeXUiUrlFactory(ThreeXUiProperties properties) {
        this(properties, false);
    }

    ThreeXUiUrlFactory(ThreeXUiProperties properties, boolean allowHttpForTests) {
        URI baseUrl = properties.baseUrl();
        if (baseUrl == null || baseUrl.getScheme() == null || baseUrl.getHost() == null
                || baseUrl.getUserInfo() != null || baseUrl.getQuery() != null
                || baseUrl.getFragment() != null) {
            throw new ThreeXUiException("Invalid 3x-ui base URL");
        }
        if (!"https".equalsIgnoreCase(baseUrl.getScheme())
                && !(allowHttpForTests && "http".equalsIgnoreCase(baseUrl.getScheme()))) {
            throw new ThreeXUiException("3x-ui base URL must use HTTPS");
        }
        String basePath = normalizePath(baseUrl.getPath());
        if (!basePath.isEmpty()) {
            throw new ThreeXUiException("3x-ui base URL must not contain a path");
        }
        String webBasePath = normalizePath(properties.webBasePath());
        String lowerPath = webBasePath.toLowerCase(Locale.ROOT);
        if (lowerPath.contains("/panel") || lowerPath.contains("/api")
                || lowerPath.endsWith("/login")) {
            throw new ThreeXUiException("Invalid 3x-ui web base path");
        }
        String authority = baseUrl.getPort() < 0
                ? baseUrl.getScheme() + "://" + baseUrl.getHost()
                : baseUrl.getScheme() + "://" + baseUrl.getHost() + ":" + baseUrl.getPort();
        this.root = authority + webBasePath;
    }

    public URI login() {
        return uri("/login");
    }

    public URI inbound(int inboundId) {
        return uri("/panel/api/inbounds/get/" + inboundId);
    }

    public URI addClient() {
        return uri("/panel/api/inbounds/addClient");
    }

    public URI updateClient(String clientUuid) {
        return uri("/panel/api/inbounds/updateClient/" + clientUuid);
    }

    public URI deleteClient(int inboundId, String clientUuid) {
        return uri("/panel/api/inbounds/" + inboundId + "/delClient/" + clientUuid);
    }

    private URI uri(String path) {
        return URI.create(root + path);
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank() || "/".equals(path.trim())) {
            return "";
        }
        String normalized = path.trim().replace('\\', '/').replaceAll("/+", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
