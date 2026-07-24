package ru.murad.myvpn.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.murad.myvpn.client.threexui.ThreeXUiInboundResponse;
import ru.murad.myvpn.config.ThreeXUiProperties;
import ru.murad.myvpn.exception.ThreeXUiException;
import ru.murad.myvpn.exception.ThreeXUiNotFoundException;

@Component
@ConditionalOnProperty(name = "vpn.provider.type", havingValue = "3x-ui")
public class ThreeXUiConfigurationMapper {

    private static final String DISPLAY_NAME = "MyVPN";

    private final ObjectMapper objectMapper;
    private final ThreeXUiProperties properties;
    private final RealitySpiderXGenerator spiderXGenerator;

    public ThreeXUiConfigurationMapper(
            ObjectMapper objectMapper,
            ThreeXUiProperties properties,
            RealitySpiderXGenerator spiderXGenerator
    ) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.spiderXGenerator = spiderXGenerator;
    }

    public VlessConfigurationData map(
            ThreeXUiInboundResponse inbound,
            String clientUuid
    ) {
        if (!"vless".equalsIgnoreCase(inbound.protocol())) {
            throw new ThreeXUiException(
                    "Unsupported VLESS transport or security configuration");
        }
        try {
            JsonNode settings = objectMapper.readTree(inbound.settings());
            JsonNode stream = objectMapper.readTree(inbound.streamSettings());
            String network = requiredText(stream, "network");
            String security = requiredText(stream, "security");
            if (!"tcp".equalsIgnoreCase(network)
                    || !"reality".equalsIgnoreCase(security)) {
                throw new ThreeXUiException(
                        "Unsupported VLESS transport or security configuration");
            }
            JsonNode target = findClient(settings, clientUuid);
            JsonNode reality = requiredObject(stream, "realitySettings");
            JsonNode publicReality = requiredObject(reality, "settings");
            int publicPort = properties.publicPortOverride() == null
                    ? inbound.port() : properties.publicPortOverride();
            return new VlessConfigurationData(
                    requiredText(target, "id"),
                    new PublicVpnHost(properties.publicHost()).value(),
                    publicPort,
                    network,
                    security,
                    optionalText(settings, "encryption", "none"),
                    optionalText(target, "flow", null),
                    firstNonBlank(reality.path("serverNames")),
                    requiredText(publicReality, "fingerprint"),
                    requiredText(publicReality, "publicKey"),
                    firstNonBlank(reality.path("shortIds")),
                    requiredSpiderX(),
                    DISPLAY_NAME);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new ThreeXUiException("Invalid 3x-ui configuration format");
        }
    }

    private String requiredSpiderX() {
        String value = spiderXGenerator.generate();
        if (value == null || value.isBlank()) {
            throw new ThreeXUiException("Incomplete VLESS configuration");
        }
        return value;
    }

    private JsonNode findClient(JsonNode settings, String clientUuid) {
        JsonNode clients = settings == null ? null : settings.get("clients");
        if (clients == null || !clients.isArray()) {
            throw new ThreeXUiException("Invalid 3x-ui configuration format");
        }
        for (JsonNode client : clients) {
            if (clientUuid.equals(client.path("id").asText(null))) {
                return client;
            }
        }
        throw new ThreeXUiNotFoundException("build client configuration");
    }

    private JsonNode requiredObject(JsonNode parent, String field) {
        JsonNode value = parent == null ? null : parent.get(field);
        if (value == null || !value.isObject()) {
            throw new ThreeXUiException("Incomplete VLESS configuration");
        }
        return value;
    }

    private String requiredText(JsonNode parent, String field) {
        String value = optionalText(parent, field, null);
        if (value == null) {
            throw new ThreeXUiException("Incomplete VLESS configuration");
        }
        return value;
    }

    private String optionalText(
            JsonNode parent,
            String field,
            String defaultValue
    ) {
        JsonNode value = parent == null ? null : parent.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            return defaultValue;
        }
        return value.asText();
    }

    private String firstNonBlank(JsonNode values) {
        if (values != null && values.isArray()) {
            for (JsonNode value : values) {
                if (value.isTextual() && !value.asText().isBlank()) {
                    return value.asText();
                }
            }
        }
        throw new ThreeXUiException("Incomplete VLESS configuration");
    }
}
