package ru.murad.myvpn.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import ru.murad.myvpn.application.auth.JwtTokenService;
import ru.murad.myvpn.application.auth.OtpCodeGenerator;
import ru.murad.myvpn.application.auth.OtpHashService;
import ru.murad.myvpn.application.auth.RefreshTokenGenerator;
import ru.murad.myvpn.application.auth.RefreshTokenHashService;
import ru.murad.myvpn.application.auth.DeviceSecretHashService;

import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;

@Configuration
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
public class AuthConfiguration {

    @Bean
    public SecureRandom authSecureRandom() {
        return new SecureRandom();
    }

    @Bean
    public OtpCodeGenerator otpCodeGenerator(SecureRandom authSecureRandom) {
        return new OtpCodeGenerator(authSecureRandom);
    }

    @Bean
    public RefreshTokenGenerator refreshTokenGenerator(SecureRandom authSecureRandom) {
        return new RefreshTokenGenerator(authSecureRandom);
    }

    @Bean
    public OtpHashService otpHashService(AuthProperties properties) {
        validate(properties);
        return new OtpHashService(properties.otp().pepper());
    }

    @Bean
    public RefreshTokenHashService refreshTokenHashService(AuthProperties properties) {
        validate(properties);
        return new RefreshTokenHashService(properties.refresh().pepper());
    }

    @Bean public DeviceSecretHashService deviceSecretHashService(DeviceSecurityProperties properties) {
        requireText(properties.secretPepper(), "security.device.secret-pepper"); return new DeviceSecretHashService(properties.secretPepper());
    }

    @Bean
    public RSAPrivateKey authPrivateKey(AuthProperties properties) {
        validate(properties);
        try {
            byte[] encoded = Base64.getDecoder().decode(properties.jwt().privateKeyBase64());
            return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(encoded));
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid AUTH_JWT_PRIVATE_KEY_BASE64", exception);
        }
    }

    @Bean
    public RSAPublicKey authPublicKey(AuthProperties properties) {
        validate(properties);
        try {
            byte[] encoded = Base64.getDecoder().decode(properties.jwt().publicKeyBase64());
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(encoded));
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid AUTH_JWT_PUBLIC_KEY_BASE64", exception);
        }
    }

    @Bean
    public JwtEncoder jwtEncoder(
            RSAPublicKey authPublicKey,
            RSAPrivateKey authPrivateKey,
            AuthProperties properties
    ) {
        RSAKey rsaKey = new RSAKey.Builder(authPublicKey)
                .privateKey(authPrivateKey)
                .keyID(properties.jwt().keyId())
                .build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));
    }

    @Bean
    public JwtDecoder jwtDecoder(RSAPublicKey authPublicKey, AuthProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(authPublicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> audienceValidator = jwt ->
                jwt.getAudience().contains(properties.jwt().audience())
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                                "invalid_token", "Required audience is missing", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.jwt().issuer()),
                audienceValidator));
        return decoder;
    }

    @Bean
    public ReactiveJwtDecoder reactiveJwtDecoder(
            RSAPublicKey authPublicKey,
            AuthProperties properties
    ) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withPublicKey(authPublicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        OAuth2TokenValidator<org.springframework.security.oauth2.jwt.Jwt> audienceValidator = jwt ->
                jwt.getAudience().contains(properties.jwt().audience())
                        ? OAuth2TokenValidatorResult.success()
                        : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                                "invalid_token", "Required audience is missing", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.jwt().issuer()),
                audienceValidator));
        return decoder;
    }

    @Bean
    public JwtTokenService jwtTokenService(
            JwtEncoder jwtEncoder,
            AuthProperties properties,
            Clock clock
    ) {
        return new JwtTokenService(jwtEncoder, properties, clock);
    }

    private void validate(AuthProperties properties) {
        requireText(properties.emailFrom(), "auth.email-from");
        requireText(properties.otp().pepper(), "auth.otp.pepper");
        requireText(properties.refresh().pepper(), "auth.refresh.pepper");
        requireText(properties.jwt().issuer(), "auth.jwt.issuer");
        requireText(properties.jwt().audience(), "auth.jwt.audience");
        requireText(properties.jwt().keyId(), "auth.jwt.key-id");
        requireText(properties.jwt().privateKeyBase64(), "auth.jwt.private-key-base64");
        requireText(properties.jwt().publicKeyBase64(), "auth.jwt.public-key-base64");
        if (properties.otp().ttl().isNegative() || properties.otp().ttl().isZero()
                || properties.otp().resendCooldown().isNegative()
                || properties.otp().maxAttempts() < 1
                || properties.jwt().accessTtl().isNegative()
                || properties.jwt().accessTtl().isZero()
                || properties.refresh().recoveryGrace().isNegative()
                || properties.refresh().recoveryGrace().isZero()) {
            throw new IllegalStateException("Auth durations and max attempts must be positive");
        }
    }

    private void requireText(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " is required when auth is enabled");
        }
    }
}
