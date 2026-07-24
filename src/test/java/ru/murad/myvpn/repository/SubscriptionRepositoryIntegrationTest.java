package ru.murad.myvpn.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.murad.myvpn.model.Subscription;
import ru.murad.myvpn.model.SubscriptionStatus;
import ru.murad.myvpn.model.TelegramUser;
import ru.murad.myvpn.model.UserRole;
import ru.murad.myvpn.model.VpnAccess;
import ru.murad.myvpn.model.VpnAccessStatus;
import ru.murad.myvpn.model.VpnTariff;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class SubscriptionRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void configurePostgresql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
    }

    @Autowired private TelegramUserRepository userRepository;
    @Autowired private VpnTariffRepository tariffRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private VpnAccessRepository accessRepository;

    @Test
    void shouldPersistAndFindCurrentSubscriptionWithVpnAccess() {
        Instant now = Instant.parse("2026-07-24T10:00:00Z");
        TelegramUser user = userRepository.save(TelegramUser.builder()
                .id(UUID.randomUUID())
                .telegramId(5001L)
                .chatId(5001L)
                .role(UserRole.USER)
                .createdAt(now)
                .updatedAt(now)
                .build());
        VpnTariff tariff = tariffRepository.findByCodeAndActiveTrue("MONTH_1").orElseThrow();
        Subscription subscription = subscriptionRepository.save(Subscription.builder()
                .id(UUID.randomUUID())
                .user(user)
                .tariff(tariff)
                .status(SubscriptionStatus.ACTIVE)
                .startsAt(now)
                .expiresAt(now.plusSeconds(30L * 24 * 60 * 60))
                .activatedByTelegramId(1001L)
                .activatedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build());
        accessRepository.saveAndFlush(VpnAccess.builder()
                .id(UUID.randomUUID())
                .subscription(subscription)
                .providerName("FAKE")
                .externalAccessId("external-5001")
                .configurationData("fake-config")
                .status(VpnAccessStatus.ACTIVE)
                .issuedAt(now)
                .createdAt(now)
                .updatedAt(now)
                .build());

        assertThat(subscriptionRepository
                .findFirstByUserTelegramIdAndStatusAndExpiresAtAfterOrderByExpiresAtDesc(
                        5001L, SubscriptionStatus.ACTIVE, now))
                .contains(subscription);
        assertThat(accessRepository.findBySubscriptionId(subscription.getId()))
                .get()
                .extracting(VpnAccess::getExternalAccessId)
                .isEqualTo("external-5001");
    }
}
