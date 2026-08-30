package ru.murad.myvpn.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "auth")
public record AuthProperties(
        boolean enabled,
        String emailFrom,
        Otp otp,
        Jwt jwt,
        Refresh refresh
) {
    public record Otp(Duration ttl, Duration resendCooldown, int maxAttempts, String pepper) {
        @Override
        public String toString() {
            return "Otp[ttl=" + ttl + ", resendCooldown=" + resendCooldown
                    + ", maxAttempts=" + maxAttempts + ", pepper=<redacted>]";
        }
    }

    public record Jwt(
            String issuer,
            String audience,
            Duration accessTtl,
            String keyId,
            String privateKeyBase64,
            String publicKeyBase64
    ) {
        @Override
        public String toString() {
            return "Jwt[issuer=" + issuer + ", audience=" + audience
                    + ", accessTtl=" + accessTtl + ", keyId=" + keyId
                    + ", privateKeyBase64=<redacted>, publicKeyBase64=<redacted>]";
        }
    }

    public record Refresh(Duration ttl, String pepper) {
        @Override
        public String toString() {
            return "Refresh[ttl=" + ttl + ", pepper=<redacted>]";
        }
    }

    @Override
    public String toString() {
        return "AuthProperties[enabled=" + enabled + ", emailFrom=<redacted>, otp="
                + otp + ", jwt=" + jwt + ", refresh=" + refresh + "]";
    }
}
