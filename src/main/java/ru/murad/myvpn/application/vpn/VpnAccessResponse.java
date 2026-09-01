package ru.murad.myvpn.application.vpn;

import ru.murad.myvpn.model.VpnEntitlement;

import java.time.Instant;

public record VpnAccessResponse(
        VpnAccessApiStatus status,
        VpnEntitlement entitlement,
        String configuration,
        VpnQuotaResponse quota,
        Instant premiumExpiresAt
) {
    @Override public String toString() {
        return "VpnAccessResponse[status=" + status + ", entitlement=" + entitlement
                + ", configuration=redacted, quota=" + quota + ", premiumExpiresAt=" + premiumExpiresAt + "]";
    }
}
