package ru.murad.myvpn.application.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.murad.myvpn.config.AuthProperties;
import ru.murad.myvpn.model.Account;
import ru.murad.myvpn.model.AccountIdentity;
import ru.murad.myvpn.model.AccountIdentityType;
import ru.murad.myvpn.model.AccountStatus;
import ru.murad.myvpn.model.AuthSession;
import ru.murad.myvpn.repository.AccountIdentityRepository;
import ru.murad.myvpn.repository.AccountRepository;
import ru.murad.myvpn.repository.AuthSessionRepository;
import ru.murad.myvpn.repository.EmailOtpChallengeRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
@RequiredArgsConstructor
public class EmailOtpVerifyService {

    private final EmailNormalizer emailNormalizer;
    private final EmailOtpChallengeRepository challengeRepository;
    private final AccountIdentityRepository identityRepository;
    private final AccountRepository accountRepository;
    private final AuthSessionRepository sessionRepository;
    private final OtpHashService otpHmac;
    private final RefreshTokenHashService refreshHmac;
    private final RefreshTokenGenerator refreshTokenGenerator;
    private final JwtTokenService jwtTokenService;
    private final AuthProperties properties;
    private final Clock clock;

    @Transactional(noRollbackFor = InvalidAuthenticationException.class)
    public AuthTokens verify(String email, String code) {
        String normalizedEmail = normalizeOrReject(email);
        if (code == null || !code.matches("\\d{6}")) {
            throw new InvalidAuthenticationException();
        }
        Instant now = clock.instant();
        var challenge = challengeRepository
                .findFirstByNormalizedEmailAndConsumedAtIsNullAndInvalidatedAtIsNullOrderByCreatedAtDesc(
                        normalizedEmail)
                .orElseThrow(InvalidAuthenticationException::new);
        if (!challenge.isActiveAt(now)) {
            challenge.invalidate(now);
            throw new InvalidAuthenticationException();
        }
        if (!otpHmac.matches(code, challenge.getCodeHash())) {
            challenge.recordFailure(now);
            throw new InvalidAuthenticationException();
        }

        challenge.consume(now);
        Account account = identityRepository.findByTypeAndNormalizedSubject(
                        AccountIdentityType.EMAIL, normalizedEmail)
                .map(AccountIdentity::getAccount)
                .orElseGet(() -> createEmailAccount(normalizedEmail, now));
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new InvalidAuthenticationException();
        }

        String refreshToken = refreshTokenGenerator.generate();
        AuthSession session = sessionRepository.saveAndFlush(AuthSession.builder()
                .id(UUID.randomUUID())
                .account(account)
                .refreshTokenHash(refreshHmac.hash(refreshToken))
                .tokenFamilyId(UUID.randomUUID())
                .rotationCounter(0)
                .expiresAt(now.plus(properties.refresh().ttl()))
                .createdAt(now)
                .build());
        String accessToken = jwtTokenService.issue(account.getId(), session.getId());
        return new AuthTokens(accessToken, refreshToken, properties.jwt().accessTtl());
    }

    private Account createEmailAccount(String normalizedEmail, Instant now) {
        Account account = accountRepository.saveAndFlush(Account.builder()
                .id(UUID.randomUUID())
                .status(AccountStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build());
        identityRepository.saveAndFlush(AccountIdentity.builder()
                .id(UUID.randomUUID())
                .account(account)
                .type(AccountIdentityType.EMAIL)
                .subject(normalizedEmail)
                .normalizedSubject(normalizedEmail)
                .verifiedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build());
        return account;
    }

    private String normalizeOrReject(String email) {
        try {
            return emailNormalizer.normalize(email);
        } catch (InvalidEmailException exception) {
            throw new InvalidAuthenticationException();
        }
    }
}
