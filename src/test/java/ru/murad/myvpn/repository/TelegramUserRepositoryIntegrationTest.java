package ru.murad.myvpn.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.PostgreSQLContainer;
import ru.murad.myvpn.model.Account;
import ru.murad.myvpn.model.AccountStatus;
import ru.murad.myvpn.model.TelegramUser;
import ru.murad.myvpn.model.UserRole;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class TelegramUserRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer<>("postgres:16.3-alpine");

    @DynamicPropertySource
    static void configurePostgresql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
    }

    @Autowired
    private TelegramUserRepository repository;

    @Autowired
    private AccountRepository accountRepository;

    @Test
    void shouldSaveAndFindUserByTelegramId() {
        TelegramUser user = createUser(1001L, 2001L);

        repository.saveAndFlush(user);

        assertThat(repository.findByTelegramId(1001L))
                .isPresent()
                .get()
                .satisfies(savedUser -> {
                    assertThat(savedUser.getId()).isEqualTo(user.getId());
                    assertThat(savedUser.getChatId()).isEqualTo(2001L);
                    assertThat(savedUser.getRole()).isEqualTo(UserRole.USER);
                });
    }

    @Test
    void shouldRejectDuplicateTelegramId() {
        repository.saveAndFlush(createUser(1001L, 2001L));

        TelegramUser duplicate = createUser(1001L, 2002L);

        assertThatThrownBy(() -> repository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private TelegramUser createUser(long telegramId, long chatId) {
        Instant now = Instant.parse("2026-07-24T10:00:00Z");
        UUID id = UUID.randomUUID();
        accountRepository.saveAndFlush(Account.builder()
                .id(id)
                .status(AccountStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build());
        return TelegramUser.builder()
                .id(id)
                .telegramId(telegramId)
                .chatId(chatId)
                .username("user")
                .firstName("Test")
                .lastName("User")
                .role(UserRole.USER)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
