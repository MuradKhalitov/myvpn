package ru.murad.myvpn.application.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.murad.myvpn.config.AuthProperties;
import ru.murad.myvpn.model.AccountStatus;
import ru.murad.myvpn.repository.AuthSessionRepository;

import java.time.Clock;
import java.time.Instant;

@Service
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
@RequiredArgsConstructor
public class RefreshTokenService {

    private final AuthSessionRepository sessionRepository;
    private final RefreshTokenHashService refreshHmac;
    private final RefreshTokenGenerator refreshTokenGenerator;
    private final JwtTokenService jwtTokenService;
    private final AuthProperties properties;
    private final Clock clock;

    @Transactional
    public AuthTokens refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new InvalidAuthenticationException();
        }
        String hash = refreshHmac.hash(refreshToken);
        var session = sessionRepository.findByRefreshTokenHashForUpdate(hash)
                .orElseThrow(InvalidAuthenticationException::new);
        Instant now = clock.instant();
        if (!session.isUsableAt(now)
                || session.getAccount().getStatus() != AccountStatus.ACTIVE) {
            throw new InvalidAuthenticationException();
        }

        String nextRefreshToken = refreshTokenGenerator.generate();
        session.rotate(refreshHmac.hash(nextRefreshToken), now, properties.refresh().ttl());
        sessionRepository.flush();
        String accessToken = jwtTokenService.issue(
                session.getAccount().getId(), session.getId());
        return new AuthTokens(accessToken, nextRefreshToken, properties.jwt().accessTtl());
    }
}
