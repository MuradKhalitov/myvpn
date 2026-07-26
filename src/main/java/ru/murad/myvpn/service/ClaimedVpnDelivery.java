package ru.murad.myvpn.service;

import ru.murad.myvpn.model.VpnDeliveryType;
import java.time.Instant;
import java.util.UUID;

public record ClaimedVpnDelivery(UUID deliveryId, UUID userId, long telegramId, UUID subscriptionId,
        UUID vpnAccessId, UUID sourcePaymentOrderId, UUID token, long generation, long subscriptionVersion, long vpnAccessVersion,
        Instant subscriptionExpiresAt, String vpnProviderName, String vpnExternalAccessId,
        String configurationFingerprint, VpnDeliveryType type, Instant expiresAt, String tariffName) {
    @Override public String toString() { return "ClaimedVpnDelivery[redacted]"; }
}
