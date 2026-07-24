package ru.murad.myvpn.client.threexui;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ThreeXUiInboundResponse(
        int id,
        int port,
        String protocol,
        String settings,
        String streamSettings
) {
}
