package ru.murad.myvpn.dto;
import java.time.Instant; import java.util.UUID;
public record PhoneVerificationStartResponse(UUID verificationId, String callPhone, String callPhonePretty, Instant expiresAt) { }
