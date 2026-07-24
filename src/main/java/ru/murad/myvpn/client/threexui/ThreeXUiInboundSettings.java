package ru.murad.myvpn.client.threexui;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ThreeXUiInboundSettings(
        List<ThreeXUiVlessClient> clients
) {
    public ThreeXUiInboundSettings {
        clients = clients == null ? List.of() : List.copyOf(clients);
    }
}
