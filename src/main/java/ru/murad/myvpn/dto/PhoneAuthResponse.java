package ru.murad.myvpn.dto;
import java.time.Instant; import java.util.UUID;
public record PhoneAuthResponse(UUID accountId, String accessToken, String refreshToken, String tokenType,
                                long expiresIn, String accessStatus, Instant accessExpiresAt) { }
