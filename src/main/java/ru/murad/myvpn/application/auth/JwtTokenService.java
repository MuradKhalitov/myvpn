package ru.murad.myvpn.application.auth;

import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import ru.murad.myvpn.config.AuthProperties;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class JwtTokenService {

    private final JwtEncoder encoder;
    private final AuthProperties properties;
    private final Clock clock;

    public JwtTokenService(JwtEncoder encoder, AuthProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public String issue(UUID accountId, UUID sessionId) {
        Instant issuedAt = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.jwt().issuer())
                .audience(List.of(properties.jwt().audience()))
                .subject(accountId.toString())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(properties.jwt().accessTtl()))
                .id(UUID.randomUUID().toString())
                .claim("sid", sessionId.toString())
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(properties.jwt().keyId())
                .build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
