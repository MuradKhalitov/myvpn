package ru.murad.myvpn.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import ru.murad.myvpn.dto.*;
import ru.murad.myvpn.model.PaymentActivationStatus;
import ru.murad.myvpn.model.PaymentStatus;
import ru.murad.myvpn.service.PaymentCheckoutService;
import ru.murad.myvpn.service.PaymentVerificationService;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PaymentControllerTest {
    @Test
    void checkoutTakesAccountOnlyFromJwtAndMapsApiDto() {
        PaymentCheckoutService checkout = mock(PaymentCheckoutService.class);
        PaymentVerificationService verification = mock(PaymentVerificationService.class);
        UUID accountId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        when(checkout.startCheckout(accountId, "MONTH_1")).thenReturn(new PaymentCheckoutResult(orderId, "Month",
                new BigDecimal("100.00"), "RUB", 30, PaymentStatus.PENDING,
                URI.create("https://yookassa.test/confirm"), Instant.parse("2026-10-01T00:00:00Z")));

        var response = new PaymentController(checkout, verification)
                .checkout(jwt(accountId), new CreateCheckoutRequest("MONTH_1")).block();

        assertThat(response).isNotNull();
        assertThat(response.getBody().paymentOrderId()).isEqualTo(orderId);
        assertThat(response.getBody().confirmationUrl()).isEqualTo(URI.create("https://yookassa.test/confirm"));
        verify(checkout).startCheckout(accountId, "MONTH_1");
        verifyNoInteractions(verification);
    }

    @Test
    void repeatedCheckoutReturnsExistingOpenOrderWithoutCreatingAnotherOrder() {
        PaymentCheckoutService checkout = mock(PaymentCheckoutService.class);
        PaymentVerificationService verification = mock(PaymentVerificationService.class);
        UUID accountId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        var existing = new PaymentCheckoutResult(orderId, "Month", new BigDecimal("100.00"), "RUB", 30,
                PaymentStatus.PENDING, URI.create("https://yookassa.test/confirm"), Instant.parse("2026-10-01T00:00:00Z"));
        when(checkout.startCheckout(accountId, "MONTH_1")).thenReturn(existing);
        PaymentController controller = new PaymentController(checkout, verification);

        assertThat(controller.checkout(jwt(accountId), new CreateCheckoutRequest("MONTH_1")).block().getBody().paymentOrderId())
                .isEqualTo(orderId);
        assertThat(controller.checkout(jwt(accountId), new CreateCheckoutRequest("MONTH_1")).block().getBody().paymentOrderId())
                .isEqualTo(orderId);
        verify(checkout, times(2)).startCheckout(accountId, "MONTH_1");
    }

    @Test
    void currentPaymentTakesAccountFromJwtAndCannotSelectForeignOrder() {
        PaymentCheckoutService checkout = mock(PaymentCheckoutService.class);
        PaymentVerificationService verification = mock(PaymentVerificationService.class);
        UUID accountId = UUID.randomUUID();
        UUID foreignOrderId = UUID.randomUUID();
        UUID ownOrderId = UUID.randomUUID();
        when(verification.verifyCurrentPayment(accountId)).thenReturn(new PaymentVerificationResult(ownOrderId,
                PaymentVerificationOutcome.SUCCEEDED, PaymentStatus.SUCCEEDED, PaymentActivationStatus.PENDING,
                Instant.parse("2026-09-02T00:00:00Z"), null, Instant.parse("2026-10-01T00:00:00Z")));

        var response = new PaymentController(checkout, verification).current(jwt(accountId)).block();

        assertThat(response.getBody().paymentOrderId()).isEqualTo(ownOrderId).isNotEqualTo(foreignOrderId);
        verify(verification).verifyCurrentPayment(accountId);
        verifyNoInteractions(checkout);
    }

    private Jwt jwt(UUID accountId) {
        return Jwt.withTokenValue("token").subject(accountId.toString()).header("alg", "none").build();
    }
}
