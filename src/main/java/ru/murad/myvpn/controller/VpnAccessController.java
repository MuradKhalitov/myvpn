package ru.murad.myvpn.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.murad.myvpn.application.vpn.CurrentVpnAccessQuery;
import ru.murad.myvpn.application.vpn.VpnAccessResponse;

import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/vpn")
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
@RequiredArgsConstructor
public class VpnAccessController {
    private final CurrentVpnAccessQuery query;

    @GetMapping("/access")
    public Mono<ResponseEntity<VpnAccessResponse>> access(@AuthenticationPrincipal Jwt jwt) {
        UUID accountId = UUID.fromString(jwt.getSubject());
        return Mono.fromCallable(() -> query.getCurrentAccess(accountId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(body -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore().mustRevalidate())
                        .header("Pragma", "no-cache")
                        .body(body));
    }
}
