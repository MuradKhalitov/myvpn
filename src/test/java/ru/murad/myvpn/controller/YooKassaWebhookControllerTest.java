package ru.murad.myvpn.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import ru.murad.myvpn.service.PaymentVerificationService;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class YooKassaWebhookControllerTest {

    private PaymentVerificationService verification;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        verification = mock(PaymentVerificationService.class);
        client = WebTestClient.bindToController(new YooKassaWebhookController(verification)).build();
    }

    @Test
    void returnPageIsSafeUtf8GetAndIgnoresQueryParameters() {
        client.get().uri("/api/payments/yookassa/return?status=succeeded&paymentOrderId=secret")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueEquals("Referrer-Policy", "no-referrer")
                .expectHeader().valueEquals("Cache-Control", "no-store, max-age=0")
                .expectHeader().valueEquals("Content-Security-Policy",
                        "default-src 'none'; style-src 'none'; base-uri 'none'; form-action 'none'")
                .expectBody(String.class)
                .value(body -> org.assertj.core.api.Assertions.assertThat(body)
                        .contains("Оплата обрабатывается.", "Вернитесь в Telegram и нажмите «Проверить оплату».")
                        .doesNotContain("secret", "paymentOrderId", "succeeded"));
        verifyNoInteractions(verification);
    }

    @Test
    void returnPageDoesNotAcceptPost() {
        client.post().uri("/api/payments/yookassa/return")
                .exchange()
                .expectStatus().isEqualTo(405);
        verifyNoInteractions(verification);
    }
}
