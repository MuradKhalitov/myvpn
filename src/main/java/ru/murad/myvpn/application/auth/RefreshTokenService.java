package ru.murad.myvpn.application.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.murad.myvpn.config.AuthProperties;
import ru.murad.myvpn.model.AccountStatus;
import ru.murad.myvpn.model.AuthSession;
import ru.murad.myvpn.repository.AuthSessionRepository;

import java.time.Clock;
import java.time.Instant;

@Service
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenService {

    private final AuthSessionRepository sessionRepository;
    private final RefreshTokenHashService refreshHmac;
    private final JwtTokenService jwtTokenService;
    private final AuthProperties properties;
    private final Clock clock;

    @Transactional
    public AuthTokens refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw invalid();
        }
        String hash = refreshHmac.hash(refreshToken);
        Instant now = clock.instant();
        var current = sessionRepository.findByRefreshTokenHashForUpdate(hash);
        if (current.isEmpty()) {
            var previous = sessionRepository.findByPreviousRefreshTokenHashForUpdate(hash)
                    .orElseThrow(this::invalid);
            validateSession(previous);
            // Only the immediately preceding generation is stored. Recovery
            // remains possible until the current credential is used to rotate.
            String recovered = refreshHmac.deriveRotatedToken(previous.getId(), previous.getRotationCounter());
            log.info("REFRESH_RECOVERY sessionId={} ROTATION_COUNTER={}",
                    previous.getId(), previous.getRotationCounter());
            return tokens(previous, recovered);
        }
        var session = current.get();
        validateSession(session);
        long nextCounter = Math.addExact(session.getRotationCounter(), 1L);
        String nextRefreshToken = refreshHmac.deriveRotatedToken(session.getId(), nextCounter);
        session.rotate(refreshHmac.hash(nextRefreshToken), now);
        sessionRepository.flush();
        log.info("REFRESH_SUCCESS sessionId={} ROTATION_COUNTER={}", session.getId(), session.getRotationCounter());
        return tokens(session, nextRefreshToken);
    }

    private void validateSession(AuthSession session) {
        if (session.getRevokedAt() != null || session.getAccount().getStatus() != AccountStatus.ACTIVE) {
            log.info("Refresh rejected: reason=SESSION_REVOKED sessionId={}", session.getId());
            throw new RefreshAuthenticationException(RefreshAuthenticationException.Reason.SESSION_REVOKED);
        }
    }

    private AuthTokens tokens(AuthSession session, String refreshToken) {
        String accessToken = jwtTokenService.issue(
                session.getAccount().getId(), session.getId());
        return new AuthTokens(accessToken, refreshToken, properties.jwt().accessTtl());
    }

    private RefreshAuthenticationException invalid() {
        log.info("REFRESH_INVALID");
        return new RefreshAuthenticationException(RefreshAuthenticationException.Reason.REFRESH_TOKEN_INVALID);
    }
}
