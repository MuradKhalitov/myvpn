package ru.murad.myvpn.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.murad.myvpn.config.AndroidReleaseProperties;
import ru.murad.myvpn.dto.AndroidAppVersionResponse;

@Service
@RequiredArgsConstructor
public class AndroidAppVersionService {
    private final AndroidReleaseProperties properties;

    public AndroidAppVersionResponse current() {
        return new AndroidAppVersionResponse(
                properties.latestVersionCode(),
                properties.latestVersionName(),
                properties.minimumSupportedVersionCode(),
                properties.apkUrl(),
                properties.changelog());
    }
}
