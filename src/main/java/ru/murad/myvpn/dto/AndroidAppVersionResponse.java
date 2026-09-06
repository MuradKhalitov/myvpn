package ru.murad.myvpn.dto;

import java.net.URI;

/** Clients must compare their versionCode with minimumSupportedVersionCode to determine whether an update is mandatory. */
public record AndroidAppVersionResponse(
        long latestVersionCode,
        String latestVersionName,
        long minimumSupportedVersionCode,
        URI apkUrl,
        String changelog
) { }
