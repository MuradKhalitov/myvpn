package ru.murad.myvpn.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.Objects;

@ConfigurationProperties(prefix = "app.android")
public record AndroidReleaseProperties(
        long latestVersionCode,
        String latestVersionName,
        long minimumSupportedVersionCode,
        URI apkUrl,
        String changelog
) {
    public AndroidReleaseProperties {
        if (latestVersionCode <= 0) throw new IllegalArgumentException("app.android.latest-version-code must be positive");
        if (minimumSupportedVersionCode <= 0 || minimumSupportedVersionCode > latestVersionCode)
            throw new IllegalArgumentException("app.android.minimum-supported-version-code is invalid");
        Objects.requireNonNull(latestVersionName, "app.android.latest-version-name");
        Objects.requireNonNull(apkUrl, "app.android.apk-url");
        Objects.requireNonNull(changelog, "app.android.changelog");
    }
}
