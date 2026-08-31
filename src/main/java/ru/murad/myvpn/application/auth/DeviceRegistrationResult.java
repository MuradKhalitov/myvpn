package ru.murad.myvpn.application.auth;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import java.util.UUID;

public record DeviceRegistrationResult(
        UUID accountId,
        @JsonUnwrapped AuthTokens tokens,
        String vpnStatus
) {
}
