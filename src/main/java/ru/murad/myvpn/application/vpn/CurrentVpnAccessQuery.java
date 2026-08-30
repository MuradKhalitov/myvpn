package ru.murad.myvpn.application.vpn;

import java.util.Optional;
import java.util.UUID;

public interface CurrentVpnAccessQuery {

    Optional<VpnAccessView> findCurrent(UUID userId);
}
