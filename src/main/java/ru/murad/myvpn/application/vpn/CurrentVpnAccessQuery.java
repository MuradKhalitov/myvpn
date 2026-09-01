package ru.murad.myvpn.application.vpn;

import java.util.UUID;

public interface CurrentVpnAccessQuery {
    VpnAccessResponse getCurrentAccess(UUID accountId);
}
