package ru.murad.myvpn.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.murad.myvpn.dto.CheckoutResponse;
import ru.murad.myvpn.dto.CreateCheckoutRequest;
import ru.murad.myvpn.dto.PaymentStatusResponse;
import ru.murad.myvpn.service.PaymentCheckoutService;
import ru.murad.myvpn.service.PaymentVerificationService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
@RequiredArgsConstructor
public class PaymentController {
    private final PaymentCheckoutService checkoutService;
    private final PaymentVerificationService verificationService;

    @PostMapping("/checkout")
    public Mono<ResponseEntity<CheckoutResponse>> checkout(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateCheckoutRequest request
    ) {
        UUID accountId = accountId(jwt);
        return Mono.fromCallable(() -> checkoutService.startCheckout(accountId, request.tariffCode()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                        new CheckoutResponse(result.paymentOrderId(), result.tariffName(), result.amount(),
                                result.currency(), result.durationDays(), result.status(),
                                result.confirmationUrl(), result.expiresAt())));
    }

    @GetMapping("/current")
    public Mono<ResponseEntity<PaymentStatusResponse>> current(@AuthenticationPrincipal Jwt jwt) {
        UUID accountId = accountId(jwt);
        return Mono.fromCallable(() -> verificationService.verifyCurrentPayment(accountId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                        new PaymentStatusResponse(result.paymentOrderId(), result.outcome(), result.paymentStatus(),
                                result.activationStatus(), result.paidAt(), result.nextCheckAt(), result.expiresAt())));
    }

    private UUID accountId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
