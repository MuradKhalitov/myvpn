package ru.murad.myvpn.client;

import ru.murad.myvpn.client.threexui.ThreeXUiInboundResponse;
import ru.murad.myvpn.client.threexui.ThreeXUiVlessClient;

import java.util.Optional;

public interface VpnConfigurationFactory {

    Optional<String> create(
            ThreeXUiInboundResponse inbound,
            ThreeXUiVlessClient client
    );
}
