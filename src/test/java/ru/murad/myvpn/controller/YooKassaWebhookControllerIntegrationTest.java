package ru.murad.myvpn.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import ru.murad.myvpn.dto.PaymentVerificationOutcome;
import ru.murad.myvpn.dto.PaymentVerificationResult;
import ru.murad.myvpn.model.PaymentActivationStatus;
import ru.murad.myvpn.model.PaymentStatus;
import ru.murad.myvpn.service.PaymentVerificationService;
import ru.murad.myvpn.support.AuthIntegrationTestSupport;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.*;

class YooKassaWebhookControllerIntegrationTest extends AuthIntegrationTestSupport {
    @MockBean private PaymentVerificationService verificationService;

    @DynamicPropertySource
    static void enableWebhook(DynamicPropertyRegistry registry) {
        registry.add("payment.yookassa.webhook-enabled", () -> "true");
    }

    @Test
    void webhookVerificationSuccessHandsOffActivationAsynchronously() {
        UUID orderId = UUID.randomUUID();
        when(verificationService.verifyProviderPayment("payment-1")).thenReturn(new PaymentVerificationResult(orderId,
                PaymentVerificationOutcome.SUCCEEDED, PaymentStatus.SUCCEEDED, PaymentActivationStatus.PENDING,
                Instant.parse("2026-09-02T00:00:00Z"), null, Instant.parse("2026-10-01T00:00:00Z")),
                new PaymentVerificationResult(orderId, PaymentVerificationOutcome.ALREADY_SUCCEEDED,
                        PaymentStatus.SUCCEEDED, PaymentActivationStatus.PENDING,
                        Instant.parse("2026-09-02T00:00:00Z"), null, Instant.parse("2026-10-01T00:00:00Z")));

        webTestClient.post().uri("/api/payments/yookassa/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"event\":\"payment.succeeded\",\"object\":{\"id\":\"payment-1\"}}")
                .exchange().expectStatus().isOk();
        webTestClient.post().uri("/api/payments/yookassa/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"event\":\"payment.succeeded\",\"object\":{\"id\":\"payment-1\"}}")
                .exchange().expectStatus().isOk();

        webTestClient.get().uri("/api/payments/yookassa/return")
                .exchange().expectStatus().isOk();
        webTestClient.get().uri("/api/payments/not-public")
                .exchange().expectStatus().isUnauthorized();

        verify(verificationService, times(2)).verifyProviderPayment("payment-1");
        verifyNoMoreInteractions(verificationService);
    }
}
