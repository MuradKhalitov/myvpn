package ru.murad.myvpn.application.vpn;

import ru.murad.myvpn.model.VpnAccessStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record VpnAccessView(
        UUID subscriptionId,
        VpnAccessStatus status,
        String providerName,
        String configuration,
        Instant expiresAt
) {

    @Override
    public String toString() {
        return "VpnAccessView[subscriptionId=" + subscriptionId
                + ", status=" + status
                + ", providerName=" + providerName
                + ", configuration=redacted"
                + ", expiresAt=" + expiresAt + "]";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof VpnAccessView that)) return false;
        return Objects.equals(subscriptionId, that.subscriptionId)
                && status == that.status
                && Objects.equals(providerName, that.providerName)
                && Objects.equals(expiresAt, that.expiresAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(subscriptionId, status, providerName, expiresAt);
    }
}
