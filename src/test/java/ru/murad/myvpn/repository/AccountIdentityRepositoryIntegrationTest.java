package ru.murad.myvpn.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.murad.myvpn.model.Account;
import ru.murad.myvpn.model.AccountIdentity;
import ru.murad.myvpn.model.AccountIdentityType;
import ru.murad.myvpn.model.AccountStatus;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class AccountIdentityRepositoryIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-30T10:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16.3-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountIdentityRepository identityRepository;

    @Test
    void savesAndFindsIdentityByTypeAndNormalizedSubject() {
        AccountIdentity identity = identity(
                account(), AccountIdentityType.TELEGRAM, "1001");
        identityRepository.saveAndFlush(identity);

        assertThat(identityRepository.findByTypeAndNormalizedSubject(
                AccountIdentityType.TELEGRAM, "1001"))
                .isPresent()
                .get()
                .satisfies(saved -> {
                    assertThat(saved.getId()).isEqualTo(identity.getId());
                    assertThat(saved.getAccount().getId())
                            .isEqualTo(identity.getAccount().getId());
                    assertThat(saved.getSubject()).isEqualTo("1001");
                });
        assertThat(identityRepository.existsByTypeAndNormalizedSubject(
                AccountIdentityType.TELEGRAM, "1001")).isTrue();
    }

    @Test
    void rejectsDuplicateTypeAndNormalizedSubject() {
        identityRepository.saveAndFlush(identity(
                account(), AccountIdentityType.TELEGRAM, "1002"));

        assertThatThrownBy(() -> identityRepository.saveAndFlush(identity(
                account(), AccountIdentityType.TELEGRAM, "1002")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsSecondIdentityOfSameTypeForAccount() {
        Account account = account();
        identityRepository.saveAndFlush(identity(
                account, AccountIdentityType.TELEGRAM, "1003"));

        assertThatThrownBy(() -> identityRepository.saveAndFlush(identity(
                account, AccountIdentityType.TELEGRAM, "1004")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Account account() {
        return accountRepository.saveAndFlush(Account.builder()
                .id(UUID.randomUUID())
                .status(AccountStatus.ACTIVE)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build());
    }

    private AccountIdentity identity(
            Account account,
            AccountIdentityType type,
            String subject
    ) {
        return AccountIdentity.builder()
                .id(UUID.randomUUID())
                .account(account)
                .type(type)
                .subject(subject)
                .normalizedSubject(subject)
                .verifiedAt(NOW)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }
}
