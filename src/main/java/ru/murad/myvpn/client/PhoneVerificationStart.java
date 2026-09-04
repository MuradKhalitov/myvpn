package ru.murad.myvpn.client;

import java.time.Instant;

public record PhoneVerificationStart(String externalCheckId, String callPhone, String callPhonePretty, Instant expiresAt) { }
