package ru.murad.myvpn.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Metrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;
import ru.murad.myvpn.config.YooKassaProperties;
import ru.murad.myvpn.dto.CreatePaymentCommand;
import ru.murad.myvpn.dto.CreatedPayment;
import ru.murad.myvpn.dto.ProviderPayment;
import ru.murad.myvpn.exception.PaymentNotFoundException;
import ru.murad.myvpn.exception.PaymentProviderPermanentException;
import ru.murad.myvpn.exception.PaymentProviderUncertainException;
import ru.murad.myvpn.model.ProviderPaymentStatus;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "payment.provider", havingValue = "yookassa")
public class YooKassaPaymentProvider implements PaymentProvider {

    private static final String PROVIDER = "yookassa";
    private final WebClient webClient;
    private final YooKassaProperties properties;
    private final ObjectMapper objectMapper;

    public YooKassaPaymentProvider(WebClient yooKassaWebClient, YooKassaProperties properties,
                                   ObjectMapper objectMapper) {
        this.webClient = yooKassaWebClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public CreatedPayment createPayment(CreatePaymentCommand command) {
        validateCommand(command);
        try {
            JsonNode node = webClient.post().uri("/payments")
                    .headers(headers -> headers.setBasicAuth(properties.shopId(), properties.secretKey()))
                    .header("Idempotence-Key", command.idempotenceKey().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of(
                            "amount", Map.of("value", command.amount().toPlainString(), "currency", "RUB"),
                            "capture", true,
                            "confirmation", Map.of("type", "redirect", "return_url", properties.returnUrl().toString()),
                            "description", command.description(),
                            "metadata", command.metadata()))
                    .exchangeToMono(response -> decode(response.statusCode(), response.headers().header("Retry-After"), response.bodyToMono(String.class)))
                    .block();
            CreatedPayment payment = created(node, command);
            metric("create", "success");
            return payment;
        } catch (PaymentProviderPermanentException | PaymentProviderUncertainException exception) {
            throw exception;
        } catch (WebClientRequestException exception) {
            metric("create", "uncertain");
            throw new PaymentProviderUncertainException("YooKassa create result is uncertain");
        } catch (RuntimeException exception) {
            metric("create", "uncertain");
            throw new PaymentProviderUncertainException("YooKassa create result is uncertain");
        }
    }

    @Override
    public ProviderPayment getPayment(String providerPaymentId) {
        if (providerPaymentId == null || providerPaymentId.isBlank() || providerPaymentId.length() > 128) {
            throw new PaymentNotFoundException();
        }
        try {
            JsonNode node = webClient.get().uri("/payments/{id}", providerPaymentId)
                    .headers(headers -> headers.setBasicAuth(properties.shopId(), properties.secretKey()))
                    .exchangeToMono(response -> decode(response.statusCode(), response.headers().header("Retry-After"), response.bodyToMono(String.class)))
                    .block();
            ProviderPayment payment = provider(node);
            metric("status", "success");
            return payment;
        } catch (PaymentProviderPermanentException | PaymentProviderUncertainException | PaymentNotFoundException exception) {
            throw exception;
        } catch (WebClientRequestException exception) {
            metric("status", "retry");
            throw new PaymentProviderUncertainException("YooKassa status is temporarily unavailable");
        } catch (RuntimeException exception) {
            metric("status", "uncertain");
            throw new PaymentProviderUncertainException("YooKassa status is temporarily unavailable");
        }
    }

    private Mono<JsonNode> decode(HttpStatusCode status, java.util.List<String> retryAfter, Mono<String> body) {
        return body.defaultIfEmpty("").flatMap(value -> {
            if (status.value() == 404) return Mono.error(new PaymentNotFoundException());
            if (status.value() == 429) {
                return Mono.error(new ru.murad.myvpn.exception.PaymentProviderRetryableException(
                        "YooKassa is temporarily unavailable", retryAfter(retryAfter)));
            }
            if (status.is5xxServerError()) {
                return Mono.error(new PaymentProviderUncertainException("YooKassa is temporarily unavailable"));
            }
            if (!status.is2xxSuccessful()) {
                return Mono.error(new PaymentProviderPermanentException("YooKassa rejected the request"));
            }
            try {
                return Mono.just(objectMapper.readTree(value));
            } catch (Exception exception) {
                return Mono.error(new PaymentProviderPermanentException("YooKassa returned an invalid response"));
            }
        });
    }

    private java.time.Duration retryAfter(java.util.List<String> values) {
        if (values == null || values.isEmpty()) return properties.retryAfterMin();
        try {
            long seconds = Long.parseLong(values.get(0));
            if (seconds < 0) return properties.retryAfterMin();
            java.time.Duration raw = java.time.Duration.ofSeconds(seconds);
            if (raw.compareTo(properties.retryAfterMin()) < 0) return properties.retryAfterMin();
            return raw.compareTo(properties.retryAfterMax()) > 0 ? properties.retryAfterMax() : raw;
        } catch (RuntimeException ex) { return properties.retryAfterMin(); }
    }

    private CreatedPayment created(JsonNode node, CreatePaymentCommand command) {
        validateObject(node, command);
        if (status(node) != ProviderPaymentStatus.PENDING) {
            throw new PaymentProviderPermanentException("YooKassa created an unsupported payment status");
        }
        String confirmation = text(node.path("confirmation").path("confirmation_url"));
        if (confirmation == null) throw new PaymentProviderPermanentException("YooKassa payment confirmation is missing");
        try {
            URI url = URI.create(confirmation);
            if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null) throw new IllegalArgumentException();
            return new CreatedPayment(text(node.path("id")), ProviderPaymentStatus.PENDING, url,
                    instant(node.path("created_at")), nullableInstant(node.path("expires_at")));
        } catch (RuntimeException exception) {
            throw new PaymentProviderPermanentException("YooKassa payment confirmation is invalid");
        }
    }

    private ProviderPayment provider(JsonNode node) {
        String id = text(node.path("id"));
        ProviderPaymentStatus status = status(node);
        BigDecimal amount = decimal(node.path("amount").path("value"));
        String currency = text(node.path("amount").path("currency"));
        UUID orderId = uuid(node.path("metadata").path("payment_order_id"));
        Instant created = instant(node.path("created_at"));
        Instant paidAt = nullableInstant(node.path("captured_at"));
        boolean paid = node.path("paid").asBoolean(false);
        if (id == null || amount == null || currency == null || created == null || orderId == null) {
            throw new PaymentProviderPermanentException("YooKassa payment object is invalid");
        }
        return new ProviderPayment(id, status, paid, amount, currency,
                text(node.path("payment_method").path("type")),
                orderId, text(node.path("recipient").path("account_id")), created, paidAt);
    }

    private void validateObject(JsonNode node, CreatePaymentCommand command) {
        if (node == null || text(node.path("id")) == null || decimal(node.path("amount").path("value")) == null
                || decimal(node.path("amount").path("value")).compareTo(command.amount()) != 0
                || !"RUB".equals(text(node.path("amount").path("currency")))
                || !command.paymentOrderId().equals(uuid(node.path("metadata").path("payment_order_id")))
                || !node.path("test").asBoolean(false) || instant(node.path("created_at")) == null) {
            throw new PaymentProviderPermanentException("YooKassa payment object does not match the order");
        }
    }

    private void validateCommand(CreatePaymentCommand command) {
        if (command == null || command.paymentOrderId() == null || command.idempotenceKey() == null
                || command.idempotenceKey().toString().length() > 64 || command.amount() == null
                || command.amount().signum() <= 0 || !"RUB".equals(command.currency())
                || command.metadata() == null || !command.paymentOrderId().toString()
                .equals(command.metadata().get("payment_order_id"))) {
            throw new PaymentProviderPermanentException("YooKassa payment command is invalid");
        }
    }

    private ProviderPaymentStatus status(JsonNode node) {
        return switch (text(node.path("status"))) {
            case "pending", "waiting_for_capture" -> ProviderPaymentStatus.PENDING;
            case "succeeded" -> ProviderPaymentStatus.SUCCEEDED;
            case "canceled" -> ProviderPaymentStatus.CANCELED;
            default -> ProviderPaymentStatus.UNKNOWN;
        };
    }

    private String text(JsonNode node) { return node == null || node.isMissingNode() || node.isNull() || node.asText().isBlank() ? null : node.asText(); }
    private BigDecimal decimal(JsonNode node) { try { String value = text(node); return value == null ? null : new BigDecimal(value); } catch (RuntimeException ex) { return null; } }
    private UUID uuid(JsonNode node) { try { String value = text(node); return value == null ? null : UUID.fromString(value); } catch (RuntimeException ex) { return null; } }
    private Instant instant(JsonNode node) { try { String value = text(node); return value == null ? null : Instant.parse(value); } catch (RuntimeException ex) { return null; } }
    private Instant nullableInstant(JsonNode node) { return instant(node); }
    private void metric(String operation, String result) { Metrics.counter("payment_provider_operation_total", "provider", PROVIDER, "operation", operation, "result", result).increment(); }
}
