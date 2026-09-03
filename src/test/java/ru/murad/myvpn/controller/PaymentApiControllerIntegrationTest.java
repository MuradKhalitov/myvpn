package ru.murad.myvpn.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import ru.murad.myvpn.application.auth.JwtTokenService;
import ru.murad.myvpn.dto.PaymentVerificationOutcome;
import ru.murad.myvpn.dto.PaymentVerificationResult;
import ru.murad.myvpn.dto.VpnTariffDto;
import ru.murad.myvpn.model.PaymentActivationStatus;
import ru.murad.myvpn.model.PaymentStatus;
import ru.murad.myvpn.service.PaymentCheckoutService;
import ru.murad.myvpn.service.PaymentVerificationService;
import ru.murad.myvpn.service.TariffService;
import ru.murad.myvpn.support.AuthIntegrationTestSupport;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;

class PaymentApiControllerIntegrationTest extends AuthIntegrationTestSupport {
    @Autowired private JwtTokenService jwtTokenService;
    @MockBean private TariffService tariffService;
    @MockBean private PaymentCheckoutService checkoutService;
    @MockBean private PaymentVerificationService verificationService;

    @Test
    void tariffsEndpointIsJwtProtectedAndReturnsApiDtos() {
        UUID accountId = UUID.randomUUID();
        when(tariffService.findAvailableTariffs()).thenReturn(List.of(new VpnTariffDto(UUID.randomUUID(), "MONTH_1",
                "Month", "desc", 30, new BigDecimal("100.00"), "RUB")));

        webTestClient.get().uri("/api/v1/tariffs").exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri("/api/v1/tariffs").header(HttpHeaders.AUTHORIZATION, bearer(accountId))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$[0].code").isEqualTo("MONTH_1");
        verify(tariffService).findAvailableTariffs();
    }

    @Test
    void checkoutUsesAuthenticatedAccountRatherThanRequestPayload() {
        UUID accountId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        when(checkoutService.startCheckout(accountId, "MONTH_1")).thenReturn(new ru.murad.myvpn.dto.PaymentCheckoutResult(
                orderId, "Month", new BigDecimal("100.00"), "RUB", 30, PaymentStatus.PENDING,
                URI.create("https://yookassa.test/confirm"), Instant.parse("2026-10-01T00:00:00Z")));

        webTestClient.post().uri("/api/v1/payments/checkout").header(HttpHeaders.AUTHORIZATION, bearer(accountId))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"tariffCode\":\"MONTH_1\",\"accountId\":\"" + UUID.randomUUID() + "\"}")
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.paymentOrderId").isEqualTo(orderId.toString());

        verify(checkoutService).startCheckout(accountId, "MONTH_1");
    }

    @Test
    void currentPaymentUsesAuthenticatedAccountAndDoesNotExposeForeignOrder() {
        UUID accountId = UUID.randomUUID();
        UUID ownOrderId = UUID.randomUUID();
        when(verificationService.verifyCurrentPayment(accountId)).thenReturn(new PaymentVerificationResult(ownOrderId,
                PaymentVerificationOutcome.SUCCEEDED, PaymentStatus.SUCCEEDED, PaymentActivationStatus.PENDING,
                Instant.parse("2026-09-02T00:00:00Z"), null, Instant.parse("2026-10-01T00:00:00Z")));

        webTestClient.get().uri("/api/v1/payments/current?paymentOrderId=" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, bearer(accountId)).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.paymentOrderId").isEqualTo(ownOrderId.toString());

        verify(verificationService).verifyCurrentPayment(accountId);
        verifyNoInteractions(checkoutService);
    }

    @Test
    void currentPaymentReturnsNotFoundOutcomeWhenAccountHasNoPaymentOrder() {
        UUID accountId = UUID.randomUUID();
        when(verificationService.verifyCurrentPayment(accountId)).thenReturn(new PaymentVerificationResult(
                PaymentVerificationOutcome.NOT_FOUND, null, null, null, null));

        webTestClient.get().uri("/api/v1/payments/current").header(HttpHeaders.AUTHORIZATION, bearer(accountId))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.outcome").isEqualTo("NOT_FOUND")
                .jsonPath("$.paymentOrderId").doesNotExist();

        verify(verificationService).verifyCurrentPayment(accountId);
    }

    private String bearer(UUID accountId) {
        return "Bearer " + jwtTokenService.issue(accountId, UUID.randomUUID());
    }
}
