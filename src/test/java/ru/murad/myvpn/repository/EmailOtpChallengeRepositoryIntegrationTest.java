package ru.murad.myvpn.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.mockito.ArgumentCaptor;
import ru.murad.myvpn.application.auth.EmailOtpRequestService;
import ru.murad.myvpn.model.EmailOtpChallenge;
import ru.murad.myvpn.support.AuthIntegrationTestSupport;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class EmailOtpChallengeRepositoryIntegrationTest extends AuthIntegrationTestSupport {

    @Autowired
    private EmailOtpChallengeRepository repository;

    @Autowired
    private EmailOtpRequestService requestService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @Transactional
    void savesHashWithoutPlaintextAndFindsActiveChallenge() {
        EmailOtpChallenge challenge = repository.saveAndFlush(challenge("a".repeat(64)));

        assertThat(repository
                .findFirstByNormalizedEmailAndConsumedAtIsNullAndInvalidatedAtIsNullOrderByCreatedAtDesc(
                        "user@example.com"))
                .contains(challenge);
        assertThat(challenge.getCodeHash()).doesNotContain("123456");
    }

    @Test
    @Transactional
    void permitsOnlyOneActiveChallengePerNormalizedEmail() {
        repository.saveAndFlush(challenge("a".repeat(64)));

        assertThatThrownBy(() -> repository.saveAndFlush(challenge("b".repeat(64))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void cooldownSuppressesSendAndAllowedResendInvalidatesPreviousChallenge() {
        requestService.request("user@example.com");
        requestService.request("user@example.com");
        verify(emailSender).sendOtp(any(), any(), any());

        UUID firstId = repository.findAll().get(0).getId();
        jdbcTemplate.update(
                "update email_otp_challenges set resend_after=now()-interval '1 second' where id=?",
                firstId);
        requestService.request("user@example.com");

        ArgumentCaptor<String> codes = ArgumentCaptor.forClass(String.class);
        verify(emailSender, times(2)).sendOtp(any(), codes.capture(), any());
        assertThat(codes.getAllValues()).hasSize(2).allMatch(code -> code.matches("\\d{6}"));
        assertThat(repository.findById(firstId).orElseThrow().getInvalidatedAt()).isNotNull();
        assertThat(repository.findAll()).hasSize(2);
    }

    private EmailOtpChallenge challenge(String hash) {
        Instant now = Instant.now();
        return EmailOtpChallenge.builder()
                .id(UUID.randomUUID())
                .normalizedEmail("user@example.com")
                .codeHash(hash)
                .expiresAt(now.plusSeconds(300))
                .resendAfter(now.plusSeconds(60))
                .attempts(0)
                .maxAttempts(5)
                .createdAt(now)
                .build();
    }
}
