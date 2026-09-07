package ru.murad.myvpn.repository;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import ru.murad.myvpn.model.Account;
import ru.murad.myvpn.model.AccountStatus;
import ru.murad.myvpn.model.AuthSession;
import ru.murad.myvpn.support.AuthIntegrationTestSupport;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthSessionRepositoryIntegrationTest extends AuthIntegrationTestSupport {

    @Test
    @Transactional
    void findsSessionByRefreshHashAndPersistsRotation() {
        Instant now = Instant.now();
        Account account = accountRepository.saveAndFlush(Account.builder()
                .id(UUID.randomUUID())
                .status(AccountStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build());
        AuthSession session = sessionRepository.saveAndFlush(AuthSession.builder()
                .id(UUID.randomUUID())
                .account(account)
                .refreshTokenHash("a".repeat(64))
                .tokenFamilyId(UUID.randomUUID())
                .createdAt(now)
                .build());

        assertThat(sessionRepository.findByRefreshTokenHashForUpdate("a".repeat(64)))
                .contains(session);
        assertThat(session.getExpiresAt()).isNull();
    }
}
