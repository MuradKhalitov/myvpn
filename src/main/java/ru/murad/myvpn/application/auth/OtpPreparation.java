package ru.murad.myvpn.application.auth;

import java.time.Duration;
import java.util.UUID;

record OtpPreparation(UUID challengeId, String recipient, String code, Duration ttl, boolean shouldSend) {

    static OtpPreparation cooldown() {
        return new OtpPreparation(null, null, null, Duration.ZERO, false);
    }

    @Override
    public String toString() {
        return "OtpPreparation[challengeId=" + challengeId
                + ", recipient=<redacted>, code=<redacted>, ttl=" + ttl
                + ", shouldSend=" + shouldSend + "]";
    }
}
