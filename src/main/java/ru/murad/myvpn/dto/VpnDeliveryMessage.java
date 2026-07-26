package ru.murad.myvpn.dto;

import java.time.Instant;

/** Secret-bearing command for the transport adapter; never log or persist it. */
public record VpnDeliveryMessage(long telegramId, String text, Instant expiresAt) {
    public VpnDeliveryMessage {
        if (telegramId <= 0 || text == null || text.isBlank() || expiresAt == null) throw new IllegalArgumentException("Delivery message is invalid");
    }
    @Override public String toString() { return "VpnDeliveryMessage[redacted,textLength=" + text.length() + "]"; }
}
