package ru.murad.myvpn.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ru.murad.myvpn.dto.PaymentVerificationOutcome;
import ru.murad.myvpn.service.PaymentVerificationService;

@RestController
@ConditionalOnProperty(name = "payment.yookassa.webhook-enabled", havingValue = "true")
public class YooKassaWebhookController {
    private final PaymentVerificationService paymentVerificationService;

    public YooKassaWebhookController(PaymentVerificationService paymentVerificationService) {
        this.paymentVerificationService = paymentVerificationService;
    }

    @PostMapping("/api/payments/yookassa/webhook")
    public ResponseEntity<Void> receive(@RequestBody JsonNode notification) {
        String event = text(notification.path("event"));
        String paymentId = text(notification.path("object").path("id"));
        if (("payment.succeeded".equals(event) || "payment.canceled".equals(event)) && paymentId != null) {
            var result = paymentVerificationService.verifyProviderPayment(paymentId);
            if (result.outcome() == PaymentVerificationOutcome.PROVIDER_UNAVAILABLE
                    || result.outcome() == PaymentVerificationOutcome.PROVIDER_RESULT_UNCERTAIN) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            }
        }
        return ResponseEntity.ok().build();
    }

    @GetMapping(value = "/api/payments/yookassa/return", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> paymentReturn() {
        String page = "<!doctype html><html lang=\"ru\"><head><meta charset=\"UTF-8\">"
                + "<title>MyVPN</title></head><body><p>Оплата обрабатывается.</p>"
                + "<p>Откройте приложение MyVPN, чтобы проверить статус оплаты.</p></body></html>";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8))
                .header("Content-Security-Policy", "default-src 'none'; style-src 'none'; base-uri 'none'; form-action 'none'")
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .header(HttpHeaders.CACHE_CONTROL, "no-store, max-age=0")
                .body(page);
    }

    private String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() || node.asText().isBlank() ? null : node.asText();
    }
}
