package ru.murad.myvpn.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.murad.myvpn.exception.ThreeXUiException;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "vpn.provider.type", havingValue = "3x-ui")
public class ThreeXUiVlessConfigurationFactory implements VpnConfigurationFactory {

    private static final String UNSUPPORTED =
            "Unsupported VLESS transport or security configuration";

    @Override
    public String create(VlessConfigurationData data) {
        if (!"tcp".equalsIgnoreCase(data.network())
                || !"reality".equalsIgnoreCase(data.security())) {
            throw new ThreeXUiException(UNSUPPORTED);
        }
        validate(data);
        List<String> parameters = new ArrayList<>();
        add(parameters, "type", "tcp");
        add(parameters, "security", "reality");
        addIfPresent(parameters, "encryption", data.encryption());
        add(parameters, "sni", data.serverName());
        add(parameters, "fp", data.fingerprint());
        add(parameters, "pbk", data.publicKey());
        add(parameters, "sid", data.shortId());
        addIfPresent(parameters, "flow", data.flow());
        addIfPresent(parameters, "spx", data.spiderX());
        try {
            String authority = new URI(
                    "vless", data.clientId(),
                    new PublicVpnHost(data.publicHost()).value(),
                    data.publicPort(),
                    null, null, null).toASCIIString();
            return authority + "?" + String.join("&", parameters)
                    + "#" + encode(data.displayName());
        } catch (URISyntaxException exception) {
            throw new ThreeXUiException("Incomplete VLESS configuration");
        }
    }

    private void validate(VlessConfigurationData data) {
        try {
            UUID.fromString(required(data.clientId()));
        } catch (RuntimeException exception) {
            throw new ThreeXUiException("Incomplete VLESS configuration");
        }
        required(data.publicHost());
        required(data.serverName());
        required(data.fingerprint());
        required(data.publicKey());
        required(data.shortId());
        required(data.displayName());
        if (data.publicPort() < 1 || data.publicPort() > 65535) {
            throw new ThreeXUiException("Incomplete VLESS configuration");
        }
    }

    private String required(String value) {
        if (value == null || value.isBlank()) {
            throw new ThreeXUiException("Incomplete VLESS configuration");
        }
        return value;
    }

    private void add(List<String> parameters, String name, String value) {
        parameters.add(name + "=" + encode(value));
    }

    private void addIfPresent(
            List<String> parameters,
            String name,
            String value
    ) {
        if (value != null && !value.isBlank()) {
            add(parameters, name, value);
        }
    }

    private String encode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte current : bytes) {
            int unsigned = current & 0xff;
            if (isUnreserved(unsigned)) {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%');
                encoded.append(Character.toUpperCase(
                        Character.forDigit((unsigned >>> 4) & 0xf, 16)));
                encoded.append(Character.toUpperCase(
                        Character.forDigit(unsigned & 0xf, 16)));
            }
        }
        return encoded.toString();
    }

    private boolean isUnreserved(int value) {
        return value >= 'a' && value <= 'z'
                || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9'
                || value == '-' || value == '.' || value == '_'
                || value == '~';
    }
}
