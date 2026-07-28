package ru.murad.myvpn.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;
import ru.murad.myvpn.config.YooKassaProperties;
import ru.murad.myvpn.dto.CreatePaymentCommand;
import ru.murad.myvpn.exception.PaymentProviderPermanentException;
import ru.murad.myvpn.exception.PaymentProviderUncertainException;
import ru.murad.myvpn.exception.PaymentProviderRetryableException;
import ru.murad.myvpn.model.ProviderPaymentStatus;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YooKassaPaymentProviderWireMockTest {
    private WireMockServer server;
    private YooKassaPaymentProvider provider;
    private final UUID orderId = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private final UUID idempotenceKey = UUID.fromString("20000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        server = new WireMockServer(0);
        server.start();
        provider = new YooKassaPaymentProvider(WebClient.builder().baseUrl(server.baseUrl()).build(),
                new YooKassaProperties(URI.create(server.baseUrl()), "test-shop", "test-secret",
                        URI.create("https://bot.example.test/payments/yookassa/return"),
                        false,
                        Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMinutes(15)), new ObjectMapper());
    }

    @AfterEach
    void tearDown() { server.stop(); }

    @Test
    void createPendingRedirectSendsBasicAuthStableIdempotenceAndMetadata() {
        server.stubFor(post(urlEqualTo("/payments")).willReturn(json(payment("pending", false))));

        var created = provider.createPayment(command());

        assertThat(created.providerPaymentId()).isEqualTo("payment-1");
        assertThat(created.status()).isEqualTo(ProviderPaymentStatus.PENDING);
        server.verify(postRequestedFor(urlEqualTo("/payments"))
                .withHeader("Authorization", equalTo("Basic dGVzdC1zaG9wOnRlc3Qtc2VjcmV0"))
                .withHeader("Idempotence-Key", equalTo(idempotenceKey.toString()))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath("$.capture", equalTo("true")))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath("$.amount.currency", equalTo("RUB")))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath("$.metadata.payment_order_id", equalTo(orderId.toString()))));
    }

    @Test
    void repeatedCreateUsesSameIdempotenceKey() {
        server.stubFor(post(urlEqualTo("/payments")).willReturn(json(payment("pending", false))));
        provider.createPayment(command());
        provider.createPayment(command());
        server.verify(2, postRequestedFor(urlEqualTo("/payments"))
                .withHeader("Idempotence-Key", equalTo(idempotenceKey.toString())));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401})
    void createRejectsPermanentHttpErrors(int status) {
        server.stubFor(post(urlEqualTo("/payments")).willReturn(aResponse().withStatus(status).withBody("{}")));
        assertThatThrownBy(() -> provider.createPayment(command())).isInstanceOf(PaymentProviderPermanentException.class)
                .hasMessageNotContaining("test-secret");
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 500})
    void createClassifiesRetryableHttpErrorsAsUncertain(int status) {
        server.stubFor(post(urlEqualTo("/payments")).willReturn(aResponse().withStatus(status).withBody("{}")));
        assertThatThrownBy(() -> provider.createPayment(command())).isInstanceOf(PaymentProviderUncertainException.class);
    }

    @Test
    void rejectsMalformedOrMismatchedCreationResponse() {
        server.stubFor(post(urlEqualTo("/payments")).willReturn(json("{not-json")));
        assertThatThrownBy(() -> provider.createPayment(command())).isInstanceOf(PaymentProviderPermanentException.class);
        server.resetAll();
        server.stubFor(post(urlEqualTo("/payments")).willReturn(json(paymentWithAmount("99.00"))));
        assertThatThrownBy(() -> provider.createPayment(command())).isInstanceOf(PaymentProviderPermanentException.class);
    }

    @Test
    void statusMapsPendingWaitingSucceededAndCanceled() {
        for (String status : new String[] {"pending", "waiting_for_capture", "succeeded", "canceled"}) {
            server.stubFor(get(urlEqualTo("/payments/" + status)).willReturn(json(payment(status, "succeeded".equals(status)))));
            var result = provider.getPayment(status);
            assertThat(result.status()).isEqualTo("succeeded".equals(status) ? ProviderPaymentStatus.SUCCEEDED
                    : "canceled".equals(status) ? ProviderPaymentStatus.CANCELED : ProviderPaymentStatus.PENDING);
        }
        server.verify(4, getRequestedFor(com.github.tomakehurst.wiremock.client.WireMock.urlMatching("/payments/.*")));
    }

    @ParameterizedTest
    @MethodSource("retryAfterCases")
    void status429NormalizesRetryAfter(String header, Duration expected) {
        var response = aResponse().withStatus(429).withBody("{}");
        if (header != null) response.withHeader("Retry-After", header);
        server.stubFor(get(urlEqualTo("/payments/payment-1")).willReturn(response));

        assertThatThrownBy(() -> provider.getPayment("payment-1"))
                .isInstanceOfSatisfying(PaymentProviderRetryableException.class,
                        exception -> assertThat(exception.retryAfter()).contains(expected));
    }

    static Stream<Arguments> retryAfterCases() {
        return Stream.of(
                Arguments.of("10", Duration.ofSeconds(10)),
                Arguments.of("0", Duration.ofSeconds(1)),
                Arguments.of("-1", Duration.ofSeconds(1)),
                Arguments.of("abc", Duration.ofSeconds(1)),
                Arguments.of(null, Duration.ofSeconds(1)),
                Arguments.of("900", Duration.ofMinutes(15)),
                Arguments.of("901", Duration.ofMinutes(15)));
    }

    private CreatePaymentCommand command() {
        return new CreatePaymentCommand(orderId, idempotenceKey, new BigDecimal("100.00"), "RUB",
                "Подписка MyVPN: Месяц", URI.create("https://bot.example.test/payments/yookassa/return"),
                Map.of("payment_order_id", orderId.toString(), "tariff_code", "MONTH_1"));
    }

    private com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    private String payment(String status, boolean paid) { return paymentWithAmount("100.00").replace("\"pending\"", "\"" + status + "\"")
            .replace("\"paid\":false", "\"paid\":" + paid); }
    private String paymentWithAmount(String amount) {
        return ("{\"id\":\"payment-1\",\"status\":\"pending\",\"paid\":false,"
                + "\"amount\":{\"value\":\"%s\",\"currency\":\"RUB\"},"
                + "\"confirmation\":{\"type\":\"redirect\",\"confirmation_url\":\"https://yookassa.test/confirm\"},"
                + "\"created_at\":\"2026-07-27T10:00:00Z\","
                + "\"metadata\":{\"payment_order_id\":\"%s\"},"
                + "\"recipient\":{\"account_id\":\"test-shop\"},\"test\":true}")
                .formatted(amount, orderId);
    }
}
