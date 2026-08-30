package ru.murad.myvpn.application.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.murad.myvpn.config.AuthProperties;
import ru.murad.myvpn.model.EmailOtpChallenge;
import ru.murad.myvpn.repository.EmailOtpChallengeRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
@RequiredArgsConstructor
public class EmailOtpTransactionService {

    private final EmailOtpChallengeRepository challengeRepository;
    private final OtpCodeGenerator codeGenerator;
    private final OtpHashService otpHmac;
    private final AuthProperties properties;
    private final Clock clock;

    @Transactional
    public OtpPreparation prepare(String normalizedEmail) {
        Instant now = clock.instant();
        challengeRepository.acquireEmailLock(normalizedEmail);
        var existing = challengeRepository
                .findFirstByNormalizedEmailAndConsumedAtIsNullAndInvalidatedAtIsNullOrderByCreatedAtDesc(
                        normalizedEmail);
        if (existing.isPresent() && now.isBefore(existing.get().getResendAfter())) {
            return OtpPreparation.cooldown();
        }
        existing.ifPresent(challenge -> {
            challenge.invalidate(now);
            challengeRepository.flush();
        });

        String code = codeGenerator.generate();
        EmailOtpChallenge challenge = EmailOtpChallenge.builder()
                .id(UUID.randomUUID())
                .normalizedEmail(normalizedEmail)
                .codeHash(otpHmac.hash(code))
                .expiresAt(now.plus(properties.otp().ttl()))
                .resendAfter(now.plus(properties.otp().resendCooldown()))
                .attempts(0)
                .maxAttempts(properties.otp().maxAttempts())
                .createdAt(now)
                .build();
        challengeRepository.saveAndFlush(challenge);
        return new OtpPreparation(
                challenge.getId(), normalizedEmail, code, properties.otp().ttl(), true);
    }

    @Transactional
    public void invalidateAfterDeliveryFailure(UUID challengeId) {
        challengeRepository.findById(challengeId)
                .ifPresent(challenge -> challenge.invalidate(clock.instant()));
    }
}
