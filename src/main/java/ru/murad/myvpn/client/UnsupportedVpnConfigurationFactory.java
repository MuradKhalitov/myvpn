package ru.murad.myvpn.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.murad.myvpn.client.threexui.ThreeXUiInboundResponse;
import ru.murad.myvpn.client.threexui.ThreeXUiVlessClient;

import java.util.Optional;

@Component
@ConditionalOnProperty(name = "vpn.provider.type", havingValue = "3x-ui")
public class UnsupportedVpnConfigurationFactory implements VpnConfigurationFactory {

    @Override
    public Optional<String> create(
            ThreeXUiInboundResponse inbound,
            ThreeXUiVlessClient client
    ) {
        return Optional.empty();
    }
}
