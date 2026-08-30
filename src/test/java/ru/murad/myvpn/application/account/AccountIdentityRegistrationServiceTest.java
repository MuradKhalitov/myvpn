package ru.murad.myvpn.application.account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.murad.myvpn.dto.RegisterTelegramUserRequest;
import ru.murad.myvpn.model.Account;
import ru.murad.myvpn.model.AccountIdentity;
import ru.murad.myvpn.model.AccountIdentityType;
import ru.murad.myvpn.model.AccountStatus;
import ru.murad.myvpn.model.TelegramUser;
import ru.murad.myvpn.repository.AccountIdentityRepository;
import ru.murad.myvpn.repository.AccountRepository;
import ru.murad.myvpn.repository.TelegramUserRepository;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "payment.activation.enabled=false",
        "vpn.delivery.enabled=false"
})
@ActiveProfiles("test")
@Testcontainers
class AccountIdentityRegistrationServiceTest {

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

    @Autowired private AccountIdentityRegistrationService service;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AccountIdentityRepository identityRepository;
    @Autowired private TelegramUserRepository telegramUserRepository;

    @BeforeEach
    void cleanDatabase() {
        identityRepository.deleteAllInBatch();
        telegramUserRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();
    }

    @Test
    void createsAccountTelegramUserAndIdentityWithSharedAccountUuid() {
        TelegramUser user = service.registerTelegramUser(request(1001L, 2001L), NOW);

        Account account = accountRepository.findById(user.getId()).orElseThrow();
        AccountIdentity identity = identityRepository
                .findByTypeAndNormalizedSubject(
                        AccountIdentityType.TELEGRAM, "1001")
                .orElseThrow();

        assertThat(account.getId()).isEqualTo(user.getId());
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(identity.getId()).isNotEqualTo(account.getId());
        assertThat(identity.getAccount().getId()).isEqualTo(account.getId());
        assertThat(identity.getSubject()).isEqualTo("1001");
        assertThat(identity.getNormalizedSubject()).isEqualTo("1001");
        assertThat(identity.getVerifiedAt()).isEqualTo(NOW);
    }

    @Test
    void repeatedRegistrationUpdatesProfileWithoutCreatingFoundationDuplicates() {
        TelegramUser first = service.registerTelegramUser(
                request(1002L, 2002L), NOW);
        Instant later = NOW.plusSeconds(60);

        TelegramUser second = service.registerTelegramUser(
                new RegisterTelegramUserRequest(
                        1002L, 3002L, "updated", "Updated", "User"),
                later);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getChatId()).isEqualTo(3002L);
        assertThat(second.getUsername()).isEqualTo("updated");
        assertThat(second.getUpdatedAt()).isEqualTo(later);
        assertThat(accountRepository.count()).isEqualTo(1);
        assertThat(telegramUserRepository.count()).isEqualTo(1);
        assertThat(identityRepository.count()).isEqualTo(1);
    }

    @Test
    void concurrentRegistrationCreatesExactlyOneFoundation() throws Exception {
        RegisterTelegramUserRequest request = request(1003L, 2003L);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<UUID> first = executor.submit(() -> {
                start.await();
                return service.registerTelegramUser(request, NOW).getId();
            });
            Future<UUID> second = executor.submit(() -> {
                start.await();
                return service.registerTelegramUser(request, NOW).getId();
            });
            start.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS))
                    .isEqualTo(second.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        assertThat(accountRepository.count()).isEqualTo(1);
        assertThat(telegramUserRepository.count()).isEqualTo(1);
        assertThat(identityRepository.count()).isEqualTo(1);
    }

    @Test
    void identityFailureRollsBackNewAccountAndTelegramUser() {
        Account existingAccount = accountRepository.saveAndFlush(Account.builder()
                .id(UUID.randomUUID())
                .status(AccountStatus.ACTIVE)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build());
        identityRepository.saveAndFlush(AccountIdentity.builder()
                .id(UUID.randomUUID())
                .account(existingAccount)
                .type(AccountIdentityType.TELEGRAM)
                .subject("1004")
                .normalizedSubject("1004")
                .verifiedAt(NOW)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build());

        assertThatThrownBy(() -> service.registerTelegramUser(
                request(1004L, 2004L), NOW))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(accountRepository.count()).isEqualTo(1);
        assertThat(telegramUserRepository.findByTelegramId(1004L)).isEmpty();
        assertThat(identityRepository.count()).isEqualTo(1);
    }

    private RegisterTelegramUserRequest request(long telegramId, long chatId) {
        return new RegisterTelegramUserRequest(
                telegramId, chatId, "user", "Test", "User");
    }
}
