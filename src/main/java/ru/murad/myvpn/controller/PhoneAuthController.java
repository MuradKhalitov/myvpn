package ru.murad.myvpn.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import org.springframework.http.server.reactive.ServerHttpRequest;
import ru.murad.myvpn.application.auth.TrustedProxyClientIpResolver;
import ru.murad.myvpn.application.auth.PhoneVerificationService;
import ru.murad.myvpn.dto.*;
import java.util.UUID;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/auth/phone")
@ConditionalOnProperty(name = {"auth.enabled", "sms.ru.enabled"}, havingValue = "true")
public class PhoneAuthController {
    private final PhoneVerificationService service;
    private final TrustedProxyClientIpResolver clientIpResolver;
    @PostMapping("/start") public Mono<PhoneVerificationStartResponse> start(@Valid @RequestBody PhoneVerificationStartRequest request,
            ServerHttpRequest httpRequest) {
        String remoteIp = clientIpResolver.resolve(httpRequest);
        return Mono.fromCallable(() -> service.start(request.phone(), remoteIp)).subscribeOn(Schedulers.boundedElastic());
    }
    @GetMapping("/{id}/status") public Mono<PhoneVerificationStatusResponse> status(@PathVariable UUID id) {
        return Mono.fromCallable(() -> service.status(id)).subscribeOn(Schedulers.boundedElastic());
    }
    @PostMapping("/exchange") public Mono<PhoneAuthResponse> exchange(@Valid @RequestBody PhoneVerificationExchangeRequest request) {
        return Mono.fromCallable(() -> service.exchange(request.verificationId(), request.exchangeToken())).subscribeOn(Schedulers.boundedElastic());
    }
}
