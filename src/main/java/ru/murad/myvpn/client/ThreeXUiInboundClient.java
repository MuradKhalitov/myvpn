package ru.murad.myvpn.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import ru.murad.myvpn.client.threexui.ThreeXUiApiResponse;
import ru.murad.myvpn.client.threexui.ThreeXUiClientRequest;
import ru.murad.myvpn.client.threexui.ThreeXUiInboundResponse;
import ru.murad.myvpn.client.threexui.ThreeXUiInboundSettings;
import ru.murad.myvpn.config.ThreeXUiProperties;
import ru.murad.myvpn.exception.ThreeXUiAuthenticationException;
import ru.murad.myvpn.exception.ThreeXUiException;
import ru.murad.myvpn.exception.ThreeXUiNotFoundException;
import ru.murad.myvpn.exception.ThreeXUiRetryableException;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Component
@ConditionalOnProperty(name = "vpn.provider.type", havingValue = "3x-ui")
public class ThreeXUiInboundClient {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ThreeXUiInboundClient.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final ThreeXUiUrlFactory urlFactory;
    private final ThreeXUiSessionManager sessionManager;
    private final ThreeXUiProperties properties;

    public ThreeXUiInboundClient(
            WebClient threeXUiWebClient,
            ObjectMapper objectMapper,
            ThreeXUiUrlFactory urlFactory,
            ThreeXUiSessionManager sessionManager,
            ThreeXUiProperties properties
    ) {
        this.webClient = threeXUiWebClient;
        this.objectMapper = objectMapper;
        this.urlFactory = urlFactory;
        this.sessionManager = sessionManager;
        this.properties = properties;
    }

    public ThreeXUiInboundResponse getInbound(ThreeXUiRequestBudget budget) {
        RawResponse response = exchange(HttpMethod.GET,
                    urlFactory.inbound(properties.inboundId()), null, budget, false);
            ThreeXUiApiResponse<ThreeXUiInboundResponse> apiResponse =
                    readResponse(response.body(), ThreeXUiInboundResponse.class);
            if (!apiResponse.success() || apiResponse.obj() == null) {
                throw new ThreeXUiException("3x-ui failed to return the configured inbound");
            }
            if (!"vless".equalsIgnoreCase(apiResponse.obj().protocol())) {
                throw new ThreeXUiException("Configured 3x-ui inbound is not VLESS");
            }
        return apiResponse.obj();
    }

    public ThreeXUiInboundSettings parseSettings(ThreeXUiInboundResponse inbound) {
        try {
            return objectMapper.readValue(inbound.settings(), ThreeXUiInboundSettings.class);
        } catch (JsonProcessingException exception) {
            throw new ThreeXUiException("Invalid 3x-ui inbound settings format");
        }
    }

    public void addClient(
            ThreeXUiClientRequest request,
            ThreeXUiRequestBudget budget
    ) {
        mutate(urlFactory.addClient(), request, "add client", budget);
    }

    public void updateClient(
            String clientUuid,
            ThreeXUiClientRequest request,
            ThreeXUiRequestBudget budget
    ) {
        mutate(urlFactory.updateClient(clientUuid), request, "update client", budget);
    }

    public ThreeXUiClientRequest prepareExpiryUpdateRequest(
            ThreeXUiInboundResponse inbound,
            String clientUuid,
            long expiryTime
    ) {
        try {
            JsonNode settings = objectMapper.readTree(inbound.settings());
            JsonNode clients = settings == null ? null : settings.get("clients");
            if (!(clients instanceof ArrayNode clientsArray)) {
                throw new ThreeXUiException("Invalid 3x-ui inbound settings format");
            }
            ObjectNode target = null;
            for (JsonNode candidate : clientsArray) {
                if (candidate instanceof ObjectNode object
                        && clientUuid.equals(object.path("id").asText(null))) {
                    target = object.deepCopy();
                    break;
                }
            }
            if (target == null) {
                throw new ThreeXUiNotFoundException("extend client");
            }
            target.put("expiryTime", expiryTime);
            ObjectNode updateSettings = objectMapper.createObjectNode();
            updateSettings.set("clients", objectMapper.createArrayNode().add(target));
            return new ThreeXUiClientRequest(
                    properties.inboundId(),
                    objectMapper.writeValueAsString(updateSettings));
        } catch (JsonProcessingException exception) {
            throw new ThreeXUiException("Invalid 3x-ui inbound settings format");
        }
    }

    public boolean otherClientsUnchanged(
            ThreeXUiInboundResponse before,
            ThreeXUiInboundResponse after,
            String targetClientUuid
    ) {
        try {
            ArrayNode beforeClients = clientsNode(before);
            ArrayNode afterClients = clientsNode(after);
            if (beforeClients.size() != afterClients.size()) {
                return false;
            }
            return clientsByIdExcluding(beforeClients, targetClientUuid)
                    .equals(clientsByIdExcluding(afterClients, targetClientUuid));
        } catch (JsonProcessingException exception) {
            throw new ThreeXUiException("Invalid 3x-ui inbound settings format");
        }
    }

    public void deleteClient(String clientUuid, ThreeXUiRequestBudget budget) {
        mutate(urlFactory.deleteClient(properties.inboundId(), clientUuid),
                null, "delete client", budget);
    }

    public String serializeSettings(ThreeXUiInboundSettings settings) {
        try {
            return objectMapper.writeValueAsString(settings);
        } catch (JsonProcessingException exception) {
            throw new ThreeXUiException("Unable to serialize 3x-ui client settings");
        }
    }

    public ThreeXUiInboundResponse getInboundForReconciliation(
            ThreeXUiRequestBudget budget
    ) {
        RawResponse response = exchange(HttpMethod.GET,
                urlFactory.inbound(properties.inboundId()), null, budget, true);
        ThreeXUiApiResponse<ThreeXUiInboundResponse> apiResponse =
                readResponse(response.body(), ThreeXUiInboundResponse.class);
        if (!apiResponse.success() || apiResponse.obj() == null) {
            throw new ThreeXUiException("3x-ui failed to return the configured inbound");
        }
        if (!"vless".equalsIgnoreCase(apiResponse.obj().protocol())) {
            throw new ThreeXUiException("Configured 3x-ui inbound is not VLESS");
        }
        return apiResponse.obj();
    }

    private void mutate(
            URI uri,
            Object body,
            String operation,
            ThreeXUiRequestBudget budget
    ) {
        RawResponse response = exchange(HttpMethod.POST, uri, body, budget, false);
        ThreeXUiApiResponse<Void> apiResponse = readResponse(response.body(), Void.class);
        if (!apiResponse.success()) {
            LOGGER.warn("3x-ui operation={} httpStatus={} apiSuccess=false "
                            + "errorCategory=business_rejection",
                    operation, response.status().value());
            throw new ThreeXUiException("3x-ui rejected operation: " + operation);
        }
        LOGGER.debug("3x-ui operation={} httpStatus={} apiSuccess=true",
                operation, response.status().value());
    }

    private RawResponse exchange(
            HttpMethod method,
            URI uri,
            Object body,
            ThreeXUiRequestBudget budget,
            boolean reconciliation
    ) {
        String cookie = sessionManager.getSessionCookie(budget);
        for (int authAttempt = 0; authAttempt < 2; authAttempt++) {
            RawResponse response = exchangeOnce(
                    method, uri, body, cookie, budget, reconciliation);
            if (response.status().value() != 404 && !isExpiredSession(response)) {
                validateStatus(response.status(), method.name().toLowerCase());
                return response;
            }
            if (authAttempt == 1) {
                if (response.status().value() == 404) {
                    validateStatus(response.status(), method.name().toLowerCase());
                }
                sessionManager.invalidate(cookie);
                throw new ThreeXUiAuthenticationException();
            }
            sessionManager.invalidate(cookie);
            cookie = sessionManager.getSessionCookie(budget);
        }
        throw new ThreeXUiAuthenticationException();
    }

    private RawResponse exchangeOnce(
            HttpMethod method,
            URI uri,
            Object body,
            String cookie,
            ThreeXUiRequestBudget budget,
            boolean reconciliation
    ) {
        try {
            if (reconciliation) {
                budget.acquireReconciliation();
                reconciliation = false;
            } else {
                budget.acquire();
            }
            WebClient.RequestBodySpec request = webClient.method(method)
                    .uri(uri)
                    .header(HttpHeaders.COOKIE, cookie)
                    .accept(MediaType.APPLICATION_JSON);
            if (body != null) {
                request.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
            }
            return request.exchangeToMono(response ->
                            response.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .map(responseBody -> new RawResponse(
                                            response.statusCode(),
                                            response.headers().asHttpHeaders()
                                                    .getFirst(HttpHeaders.LOCATION),
                                            response.headers().contentType().orElse(null),
                                            responseBody)))
                    .block();
        } catch (WebClientRequestException exception) {
            if (exception.getCause() instanceof javax.net.ssl.SSLException) {
                throw new ThreeXUiException("3x-ui TLS validation failed");
            }
            throw new ThreeXUiRetryableException(
                    "Temporary network failure during 3x-ui request");
        }
    }

    private boolean isExpiredSession(RawResponse response) {
        if (response.status().value() == 401 || response.status().value() == 403) {
            return true;
        }
        if (response.status().is3xxRedirection()) {
            return response.location() != null
                    && response.location().toLowerCase().contains("login");
        }
        return response.contentType() != null
                && response.contentType().isCompatibleWith(MediaType.TEXT_HTML)
                && response.body().toLowerCase().contains("login");
    }

    private void validateStatus(HttpStatusCode status, String operation) {
        int code = status.value();
        if (code == 404) {
            throw new ThreeXUiNotFoundException(operation);
        }
        if (code == 429 || code == 502 || code == 503 || code == 504) {
            throw new ThreeXUiRetryableException(
                    "Temporary 3x-ui HTTP failure: " + code);
        }
        if (!status.is2xxSuccessful()) {
            throw new ThreeXUiException("3x-ui HTTP failure: " + code);
        }
    }

    private <T> ThreeXUiApiResponse<T> readResponse(String body, Class<T> objectType) {
        try {
            JavaType type = objectMapper.getTypeFactory()
                    .constructParametricType(ThreeXUiApiResponse.class, objectType);
            return objectMapper.readValue(body, type);
        } catch (JsonProcessingException exception) {
            throw new ThreeXUiException("Invalid 3x-ui API response format");
        }
    }

    private ArrayNode clientsNode(
            ThreeXUiInboundResponse inbound
    ) throws JsonProcessingException {
        JsonNode settings = objectMapper.readTree(inbound.settings());
        JsonNode clients = settings == null ? null : settings.get("clients");
        if (clients instanceof ArrayNode array) {
            return array;
        }
        throw new ThreeXUiException("Invalid 3x-ui inbound settings format");
    }

    private Map<String, JsonNode> clientsByIdExcluding(
            ArrayNode clients,
            String excludedClientUuid
    ) {
        Map<String, JsonNode> result = new HashMap<>();
        for (JsonNode client : clients) {
            String id = client.path("id").asText(null);
            if (id == null) {
                throw new ThreeXUiException("Invalid 3x-ui inbound settings format");
            }
            if (!excludedClientUuid.equals(id)) {
                result.put(id, client);
            }
        }
        return result;
    }

    public void pause(int attempt) {
        long initial = properties.retryInitialDelay().toMillis();
        long maximum = properties.retryMaxDelay().toMillis();
        long exponential = initial * (1L << Math.min(attempt - 1, 20));
        long capped = Math.min(exponential, maximum);
        long jittered = capped == 0 ? 0
                : ThreadLocalRandom.current().nextLong(capped / 2, capped + 1);
        try {
            Thread.sleep(jittered);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ThreeXUiException("3x-ui retry was interrupted", exception);
        }
    }

    static record RawResponse(
            HttpStatusCode status,
            String location,
            MediaType contentType,
            String body
    ) {

        @Override
        public String toString() {
            return "RawResponse[status=" + status
                    + ", locationRedacted=true, bodyRedacted=true]";
        }
    }
}
