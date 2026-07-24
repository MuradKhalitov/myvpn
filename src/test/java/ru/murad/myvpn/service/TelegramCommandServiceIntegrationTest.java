package ru.murad.myvpn.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.murad.myvpn.dto.TelegramIncomingMessage;
import ru.murad.myvpn.model.SubscriptionStatus;
import ru.murad.myvpn.model.VpnAccessStatus;
import ru.murad.myvpn.repository.SubscriptionRepository;
import ru.murad.myvpn.repository.TelegramUserRepository;
import ru.murad.myvpn.repository.VpnAccessRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
class TelegramCommandServiceIntegrationTest {

    private static final long ADMIN_ID = 100L;
    private static final long USER_ID = 200L;

    @Container
    static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer("postgres:18-alpine");

    @DynamicPropertySource
    static void configureApplication(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
        registry.add("telegram.admin-ids", () -> String.valueOf(ADMIN_ID));
    }

    @Autowired
    private TelegramCommandService commandService;

    @Autowired
    private TelegramUserRepository userRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private VpnAccessRepository vpnAccessRepository;

    @Test
    void shouldCompleteFakeVpnSubscriptionLifecycleThroughCommands() {
        String welcome = commandService.handle(userMessage("/start"));
        String tariffs = commandService.handle(userMessage("/tariffs"));
        String activation = commandService.handle(adminMessage("/activate " + USER_ID + " MONTH_1"));
        String subscription = commandService.handle(userMessage("/subscription"));
        String userDetails = commandService.handle(adminMessage("/user " + USER_ID));
        String revocation = commandService.handle(adminMessage("/revoke " + USER_ID));

        assertThat(welcome).contains("My VPN", "/tariffs", "/subscription");
        assertThat(tariffs).contains("MONTH_1", "90.00 RUB", "YEAR_1", "720.00 RUB");
        assertThat(activation).isNotBlank();
        assertThat(subscription).contains("FAKE", "fake-vpn://");
        assertThat(userDetails).contains(String.valueOf(USER_ID), "@integration_user", "FAKE");
        assertThat(revocation).isNotBlank();

        var user = userRepository.findByTelegramId(USER_ID).orElseThrow();
        var persistedSubscription = subscriptionRepository
                .findFirstByUserTelegramIdAndStatusOrderByExpiresAtDesc(
                        USER_ID, SubscriptionStatus.REVOKED)
                .orElseThrow();
        var access = vpnAccessRepository.findBySubscriptionId(persistedSubscription.getId())
                .orElseThrow();

        assertThat(user.getChatId()).isEqualTo(USER_ID);
        assertThat(persistedSubscription.getStatus()).isEqualTo(SubscriptionStatus.REVOKED);
        assertThat(access.getStatus()).isEqualTo(VpnAccessStatus.REVOKED);
        assertThat(access.getConfigurationData()).startsWith("fake-vpn://");
    }

    @Test
    void shouldRejectAdministrativeCommandFromRegularUser() {
        commandService.handle(userMessage("/start"));

        String response = commandService.handle(userMessage(
                "/activate " + USER_ID + " MONTH_1"));

        assertThat(response).isNotBlank();
        assertThat(subscriptionRepository.count()).isZero();
        assertThat(vpnAccessRepository.count()).isZero();
    }

    private TelegramIncomingMessage userMessage(String text) {
        return new TelegramIncomingMessage(
                USER_ID, USER_ID, "integration_user", "Integration", "User", text);
    }

    private TelegramIncomingMessage adminMessage(String text) {
        return new TelegramIncomingMessage(
                ADMIN_ID, ADMIN_ID, "integration_admin", "Admin", null, text);
    }
}
