package ru.murad.myvpn.application.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.murad.myvpn.model.EmailOtpChallenge;
import ru.murad.myvpn.repository.AccountIdentityRepository;
import ru.murad.myvpn.repository.AccountRepository;
import ru.murad.myvpn.repository.AuthSessionRepository;
import ru.murad.myvpn.repository.EmailOtpChallengeRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailOtpVerifyServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");

    @Spy
    private EmailNormalizer emailNormalizer = new EmailNormalizer();
    @Mock private EmailOtpChallengeRepository challengeRepository;
    @Mock private AccountIdentityRepository identityRepository;
    @Mock private AccountRepository accountRepository;
    @Mock private AuthSessionRepository sessionRepository;
    @Mock private OtpHashService otpHmac;
    @Mock private RefreshTokenHashService refreshHmac;
    @Mock private RefreshTokenGenerator refreshTokenGenerator;
    @Mock private JwtTokenService jwtTokenService;
    @Mock private ru.murad.myvpn.config.AuthProperties properties;
    @Spy private Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @InjectMocks
    private EmailOtpVerifyService service;

    @Test
    void wrongCodeIncrementsAttemptsAndEventuallyInvalidatesChallenge() {
        EmailOtpChallenge challenge = challenge(4, 5, NOW.plusSeconds(300));
        when(challengeRepository
                .findFirstByNormalizedEmailAndConsumedAtIsNullAndInvalidatedAtIsNullOrderByCreatedAtDesc(
                        "user@example.com"))
                .thenReturn(Optional.of(challenge));
        when(otpHmac.matches("000000", challenge.getCodeHash())).thenReturn(false);

        assertThatThrownBy(() -> service.verify("user@example.com", "000000"))
                .isInstanceOf(InvalidAuthenticationException.class);

        assertThat(challenge.getAttempts()).isEqualTo(5);
        assertThat(challenge.getInvalidatedAt()).isEqualTo(NOW);
    }

    @Test
    void expiredChallengeUsesSameGenericFailure() {
        EmailOtpChallenge challenge = challenge(0, 5, NOW);
        when(challengeRepository
                .findFirstByNormalizedEmailAndConsumedAtIsNullAndInvalidatedAtIsNullOrderByCreatedAtDesc(
                        "user@example.com"))
                .thenReturn(Optional.of(challenge));

        assertThatThrownBy(() -> service.verify("user@example.com", "123456"))
                .isInstanceOf(InvalidAuthenticationException.class)
                .hasMessage("Authentication data is invalid or expired");
        assertThat(challenge.getInvalidatedAt()).isEqualTo(NOW);
    }

    private EmailOtpChallenge challenge(int attempts, int maxAttempts, Instant expiresAt) {
        return EmailOtpChallenge.builder()
                .id(UUID.randomUUID())
                .normalizedEmail("user@example.com")
                .codeHash("a".repeat(64))
                .expiresAt(expiresAt)
                .resendAfter(NOW.plusSeconds(60))
                .attempts(attempts)
                .maxAttempts(maxAttempts)
                .createdAt(NOW)
                .build();
    }
}
