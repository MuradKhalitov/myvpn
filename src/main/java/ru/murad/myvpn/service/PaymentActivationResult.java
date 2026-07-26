package ru.murad.myvpn.service;

import java.time.Instant;

public record PaymentActivationResult(String externalClientId, Instant targetExpiresAt,
                                      String configurationData) {
    @Override public String toString() { return "PaymentActivationResult[redacted]"; }
}
