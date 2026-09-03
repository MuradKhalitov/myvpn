package ru.murad.myvpn.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.murad.myvpn.dto.TariffResponse;
import ru.murad.myvpn.service.TariffService;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/v1/tariffs")
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
@RequiredArgsConstructor
public class TariffController {
    private final TariffService tariffService;

    @GetMapping
    public Mono<org.springframework.http.ResponseEntity<List<TariffResponse>>> list() {
        return Mono.fromCallable(tariffService::findAvailableTariffs)
                .subscribeOn(Schedulers.boundedElastic())
                .map(tariffs -> tariffs.stream().map(TariffResponse::from).toList())
                .map(tariffs -> org.springframework.http.ResponseEntity.ok()
                        .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                        .body(tariffs));
    }
}
