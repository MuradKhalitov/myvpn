package ru.murad.myvpn.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.murad.myvpn.model.Account;
import ru.murad.myvpn.model.AccountStatus;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class AccountRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16.3-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private AccountRepository repository;

    @Test
    void savesAndFindsAccountByInternalUuid() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-30T10:00:00Z");

        repository.saveAndFlush(Account.builder()
                .id(id)
                .status(AccountStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build());

        assertThat(repository.findById(id))
                .isPresent()
                .get()
                .satisfies(account -> {
                    assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
                    assertThat(account.getCreatedAt()).isEqualTo(now);
                    assertThat(account.getVersion()).isZero();
                });
    }
}
