package ru.murad.myvpn.application.auth;

import org.junit.jupiter.api.Test;
import ru.murad.myvpn.config.AuthProperties;
import ru.murad.myvpn.model.Account;
import ru.murad.myvpn.model.AccountStatus;
import ru.murad.myvpn.model.AuthSession;
import ru.murad.myvpn.repository.AuthSessionRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefreshTokenServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");

    @Test void rotatesRefreshTokenWithoutTimeExpiringSession() {
        Fixture f = fixture();
        when(f.hash.hash("old-token")).thenReturn("old-hash");
        when(f.hash.deriveRotatedToken(f.session.getId(), 1)).thenReturn("next-token");
        when(f.hash.hash("next-token")).thenReturn("next-hash");
        when(f.repository.findByRefreshTokenHashForUpdate("old-hash")).thenReturn(Optional.of(f.session));

        AuthTokens tokens = f.service.refresh("old-token");

        assertThat(tokens.getRefreshToken()).isEqualTo("next-token");
        assertThat(f.session.getRefreshTokenHash()).isEqualTo("next-hash");
        assertThat(f.session.getPreviousRefreshTokenHash()).isEqualTo("old-hash");
        assertThat(f.session.getPreviousRefreshValidUntil()).isEqualTo(NOW.plusSeconds(120));
        assertThat(f.session.getRotationCounter()).isEqualTo(1);
        assertThat(f.session.getExpiresAt()).isNull();
        verify(f.repository).flush();
    }

    @Test void previousTokenRecoversSameRotationAfterLostResponse() {
        Fixture f = fixture();
        f.session.rotate("current-hash", NOW.minusSeconds(30), NOW.plusSeconds(90));
        when(f.hash.hash("old-token")).thenReturn("old-hash");
        when(f.repository.findByRefreshTokenHashForUpdate("old-hash")).thenReturn(Optional.empty());
        when(f.repository.findByPreviousRefreshTokenHashForUpdate("old-hash")).thenReturn(Optional.of(f.session));
        when(f.hash.deriveRotatedToken(f.session.getId(), 1)).thenReturn("same-current-token");

        AuthTokens recovered = f.service.refresh("old-token");

        assertThat(recovered.getRefreshToken()).isEqualTo("same-current-token");
        assertThat(f.session.getRotationCounter()).isEqualTo(1);
    }

    @Test void previousTokenCannotRecoverOutsideBoundedGrace() {
        Fixture f = fixture();
        f.session.rotate("current-hash", NOW.minusSeconds(180), NOW.minusSeconds(60));
        when(f.hash.hash("old-token")).thenReturn("old-hash");
        when(f.repository.findByPreviousRefreshTokenHashForUpdate("old-hash")).thenReturn(Optional.of(f.session));

        assertThatThrownBy(() -> f.service.refresh("old-token"))
                .isInstanceOf(RefreshAuthenticationException.class)
                .extracting("reason").isEqualTo(RefreshAuthenticationException.Reason.REFRESH_TOKEN_INVALID);
    }

    @Test void revokedSessionIsExplicitlyRejectedEvenWithoutExpiry() {
        Fixture f = fixture();
        f.session.revoke(NOW.minusSeconds(1));
        when(f.hash.hash("old-token")).thenReturn("old-hash");
        when(f.repository.findByRefreshTokenHashForUpdate("old-hash")).thenReturn(Optional.of(f.session));

        assertThatThrownBy(() -> f.service.refresh("old-token"))
                .isInstanceOf(RefreshAuthenticationException.class)
                .extracting("reason").isEqualTo(RefreshAuthenticationException.Reason.SESSION_REVOKED);
    }

    private Fixture fixture() {
        Account account = Account.builder().id(UUID.randomUUID()).status(AccountStatus.ACTIVE)
                .createdAt(NOW.minus(Duration.ofDays(800))).updatedAt(NOW).build();
        AuthSession session = AuthSession.builder().id(UUID.randomUUID()).account(account)
                .refreshTokenHash("old-hash").tokenFamilyId(UUID.randomUUID())
                .createdAt(NOW.minus(Duration.ofDays(800))).build();
        AuthSessionRepository repository = mock(AuthSessionRepository.class);
        RefreshTokenHashService hash = mock(RefreshTokenHashService.class);
        JwtTokenService jwt = mock(JwtTokenService.class);
        when(jwt.issue(account.getId(), session.getId())).thenReturn("access-token");
        RefreshTokenService service = new RefreshTokenService(repository, hash, jwt, properties(), Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(repository, hash, session, service);
    }

    private AuthProperties properties() {
        return new AuthProperties(true, "from@example.com",
                new AuthProperties.Otp(Duration.ofMinutes(5), Duration.ofMinutes(1), 5, "otp"),
                new AuthProperties.Jwt("https://auth.myvpn.local", "android", Duration.ofMinutes(15), "key", "private", "public"),
                new AuthProperties.Refresh(Duration.ofMinutes(2), "refresh"));
    }

    private record Fixture(AuthSessionRepository repository, RefreshTokenHashService hash,
                           AuthSession session, RefreshTokenService service) { }
}
