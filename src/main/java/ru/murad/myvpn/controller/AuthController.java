package ru.murad.myvpn.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.murad.myvpn.application.auth.AuthTokens;
import ru.murad.myvpn.application.auth.EmailOtpRequestService;
import ru.murad.myvpn.application.auth.EmailOtpVerifyService;
import ru.murad.myvpn.application.auth.LogoutService;
import ru.murad.myvpn.application.auth.RefreshTokenService;
import ru.murad.myvpn.dto.OtpRequest;
import ru.murad.myvpn.dto.OtpVerifyRequest;
import ru.murad.myvpn.dto.RefreshTokenRequest;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final EmailOtpRequestService requestService;
    private final EmailOtpVerifyService verifyService;
    private final RefreshTokenService refreshTokenService;
    private final LogoutService logoutService;

    @PostMapping("/otp/request")
    public Mono<ResponseEntity<Void>> request(@RequestBody OtpRequest request) {
        return Mono.fromRunnable(() -> requestService.request(request.email()))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(ResponseEntity.status(HttpStatus.ACCEPTED).build());
    }

    @PostMapping("/otp/verify")
    public Mono<AuthTokens> verify(@RequestBody OtpVerifyRequest request) {
        return Mono.fromCallable(() -> verifyService.verify(request.email(), request.code()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/refresh")
    public Mono<AuthTokens> refresh(@RequestBody RefreshTokenRequest request) {
        log.info("REFRESH_REQUEST_STARTED");
        return Mono.fromCallable(() -> refreshTokenService.refresh(request.refreshToken()))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(tokens -> log.info("REFRESH_TRANSACTION_FINISHED result=SUCCESS"))
                .doOnError(failure -> log.warn("REFRESH_TRANSACTION_FINISHED result=ERROR category={}",
                        failure instanceof org.springframework.dao.DataAccessException ? "DB"
                                : failure instanceof org.springframework.transaction.TransactionException ? "TRANSACTION"
                                : failure instanceof ru.murad.myvpn.application.auth.RefreshAuthenticationException ? "AUTH_DOMAIN"
                                : failure instanceof org.springframework.security.oauth2.jwt.JwtException ? "JWT"
                                : "INTERNAL"));
    }

    @PostMapping("/logout")
    public Mono<ResponseEntity<Void>> logout(@AuthenticationPrincipal Jwt jwt) {
        return Mono.fromRunnable(() -> logoutService.logout(
                        UUID.fromString(jwt.getClaimAsString("sid")),
                        UUID.fromString(jwt.getSubject())))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(ResponseEntity.noContent().build());
    }
}
