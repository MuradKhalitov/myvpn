package ru.murad.myvpn.application.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import ru.murad.myvpn.config.AuthProperties;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenServiceTest {

    @Test
    void issuesRequiredClaimsAndRsaSignature() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        var publicKey = (RSAPublicKey) pair.getPublic();
        var privateKey = (RSAPrivateKey) pair.getPrivate();
        RSAKey jwk = new RSAKey.Builder(publicKey).privateKey(privateKey).keyID("test-key").build();
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        AuthProperties properties = new AuthProperties(true, "from@example.com",
                new AuthProperties.Otp(Duration.ofMinutes(5), Duration.ofMinutes(1), 5, "otp"),
                new AuthProperties.Jwt("https://auth.myvpn.local", "myvpn-android", Duration.ofMinutes(15),
                        "test-key", "private", "public"),
                new AuthProperties.Refresh(Duration.ofDays(30), "refresh"));
        JwtTokenService service = new JwtTokenService(
                encoder, properties, Clock.fixed(now, ZoneOffset.UTC));
        UUID accountId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        var jwt = NimbusJwtDecoder.withPublicKey(publicKey).build()
                .decode(service.issue(accountId, sessionId));

        assertThat(jwt.getSubject()).isEqualTo(accountId.toString());
        assertThat(jwt.getClaimAsString("sid")).isEqualTo(sessionId.toString());
        assertThat(jwt.getIssuer().toString()).isEqualTo("https://auth.myvpn.local");
        assertThat(jwt.getAudience()).containsExactly("myvpn-android");
        assertThat(jwt.getIssuedAt()).isEqualTo(now);
        assertThat(jwt.getExpiresAt()).isEqualTo(now.plus(Duration.ofMinutes(15)));
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getHeaders().get("kid")).isEqualTo("test-key");
    }
}
