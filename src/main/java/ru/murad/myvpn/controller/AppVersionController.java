package ru.murad.myvpn.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.murad.myvpn.dto.AndroidAppVersionResponse;
import ru.murad.myvpn.service.AndroidAppVersionService;

@RestController
@RequestMapping("/api/v1/app/version")
@RequiredArgsConstructor
public class AppVersionController {
    private final AndroidAppVersionService appVersionService;

    @GetMapping
    public AndroidAppVersionResponse current() {
        return appVersionService.current();
    }
}
