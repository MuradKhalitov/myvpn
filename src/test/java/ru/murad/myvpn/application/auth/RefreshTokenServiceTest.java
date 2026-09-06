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
import static org.mockito.Mockito.when;

class RefreshTokenServiceTest {

    @Test
    void rotatesRefreshTokenAndRejectsUnknownOldToken() {
        Instant now = Instant.parse("2026-08-30T12:00:00Z");
        Account account = Account.builder().id(UUID.randomUUID()).status(AccountStatus.ACTIVE)
                .createdAt(now).updatedAt(now).build();
        AuthSession session = AuthSession.builder().id(UUID.randomUUID()).account(account)
                .refreshTokenHash("old-hash").tokenFamilyId(UUID.randomUUID())
                .expiresAt(now.plusSeconds(3600)).createdAt(now).build();
        AuthSessionRepository repository = mock(AuthSessionRepository.class);
        RefreshTokenHashService hashService = mock(RefreshTokenHashService.class);
        RefreshTokenGenerator generator = mock(RefreshTokenGenerator.class);
        JwtTokenService jwt = mock(JwtTokenService.class);
        AuthProperties properties = properties();
        when(hashService.hash("old-token")).thenReturn("old-hash");
        when(hashService.hash("next-token")).thenReturn("next-hash");
        when(repository.findByRefreshTokenHashForUpdate("old-hash")).thenReturn(Optional.of(session));
        when(generator.generate()).thenReturn("next-token");
        when(jwt.issue(account.getId(), session.getId())).thenReturn("access-token");
        RefreshTokenService service = new RefreshTokenService(repository, hashService, generator,
                jwt, properties, Clock.fixed(now, ZoneOffset.UTC));

        AuthTokens tokens = service.refresh("old-token");

        assertThat(tokens.getRefreshToken()).isEqualTo("next-token");
        assertThat(session.getRefreshTokenHash()).isEqualTo("next-hash");
        assertThat(session.getRotationCounter()).isEqualTo(1);
        assertThat(session.getExpiresAt()).isEqualTo(now.plus(Duration.ofDays(365)));

        when(hashService.hash("reused-token")).thenReturn("missing-hash");
        assertThatThrownBy(() -> service.refresh("reused-token"))
                .isInstanceOf(InvalidAuthenticationException.class);
    }

    private AuthProperties properties() {
        return new AuthProperties(true, "from@example.com",
                new AuthProperties.Otp(Duration.ofMinutes(5), Duration.ofMinutes(1), 5, "otp"),
                new AuthProperties.Jwt("https://auth.myvpn.local", "android", Duration.ofMinutes(15),
                        "key", "private", "public"),
                new AuthProperties.Refresh(Duration.ofDays(365), "refresh"));
    }
}
