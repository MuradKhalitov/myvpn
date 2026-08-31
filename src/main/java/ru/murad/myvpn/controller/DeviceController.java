package ru.murad.myvpn.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.murad.myvpn.application.auth.DeviceRegistrationResult;
import ru.murad.myvpn.application.auth.DeviceRegistrationService;
import ru.murad.myvpn.dto.DeviceRegisterRequest;

@RestController
@RequestMapping("/api/v1/device")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
public class DeviceController {

    private final DeviceRegistrationService service;

    @PostMapping("/register")
    public Mono<DeviceRegistrationResult> register(@RequestBody DeviceRegisterRequest request) {
        return Mono.fromCallable(() -> service.register(request.installId(), request.deviceSecret()))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
