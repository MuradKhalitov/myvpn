package ru.murad.myvpn.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.murad.myvpn.dto.PaymentVerificationOutcome;
import ru.murad.myvpn.dto.PaymentVerificationResult;
import ru.murad.myvpn.model.PaymentActivationStatus;
import ru.murad.myvpn.model.PaymentStatus;
import ru.murad.myvpn.service.PaymentVerificationService;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class YooKassaWebhookControllerTest {
    @Test
    void succeededWebhookDelegatesVerificationAndLeavesActivationForWorker() throws Exception {
        PaymentVerificationService verification = mock(PaymentVerificationService.class);
        UUID orderId = UUID.randomUUID();
        when(verification.verifyProviderPayment("payment-1")).thenReturn(new PaymentVerificationResult(orderId,
                PaymentVerificationOutcome.SUCCEEDED, PaymentStatus.SUCCEEDED, PaymentActivationStatus.PENDING,
                Instant.parse("2026-09-02T00:00:00Z"), null, Instant.parse("2026-10-01T00:00:00Z")));
        var notification = new ObjectMapper().readTree("{\"event\":\"payment.succeeded\",\"object\":{\"id\":\"payment-1\"}}");

        var response = new YooKassaWebhookController(verification).receive(notification);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(verification).verifyProviderPayment("payment-1");
        verifyNoMoreInteractions(verification);
    }
}
