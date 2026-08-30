package ru.murad.myvpn.application.auth;

import org.junit.jupiter.api.Test;
import ru.murad.myvpn.config.AuthProperties;
import ru.murad.myvpn.dto.OtpRequest;
import ru.murad.myvpn.dto.OtpVerifyRequest;
import ru.murad.myvpn.dto.RefreshTokenRequest;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthSecretRepresentationTest {

    @Test
    void stringRepresentationsRedactAllAuthSecretsAndFullEmail() {
        String email = "secret.user@example.com";
        String otp = "012345";
        String refresh = "raw-refresh-token";
        String access = "raw-access-token";
        String privateKey = "private-key-material";
        String publicKey = "public-key-material";
        String otpPepper = "otp-pepper";
        String refreshPepper = "refresh-pepper";
        AuthProperties properties = new AuthProperties(true, email,
                new AuthProperties.Otp(Duration.ofMinutes(5), Duration.ofMinutes(1), 5, otpPepper),
                new AuthProperties.Jwt("https://auth.myvpn.local", "android", Duration.ofMinutes(15),
                        "kid", privateKey, publicKey),
                new AuthProperties.Refresh(Duration.ofDays(30), refreshPepper));

        assertThat(new OtpRequest(email).toString()).doesNotContain(email);
        assertThat(new OtpVerifyRequest(email, otp).toString()).doesNotContain(email, otp);
        assertThat(new RefreshTokenRequest(refresh).toString()).doesNotContain(refresh);
        assertThat(new AuthTokens(access, refresh, Duration.ofMinutes(15)).toString())
                .doesNotContain(access, refresh);
        assertThat(new OtpPreparation(UUID.randomUUID(), email, otp, Duration.ofMinutes(5), true)
                .toString()).doesNotContain(email, otp);
        assertThat(properties.toString())
                .doesNotContain(email, privateKey, publicKey, otpPepper, refreshPepper);
        assertThat(properties.otp().toString()).doesNotContain(otpPepper);
        assertThat(properties.jwt().toString()).doesNotContain(privateKey, publicKey);
        assertThat(properties.refresh().toString()).doesNotContain(refreshPepper);
    }
}
