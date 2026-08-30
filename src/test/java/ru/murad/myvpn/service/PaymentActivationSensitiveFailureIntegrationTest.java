package ru.murad.myvpn.service;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.murad.myvpn.client.VpnProvider;
import ru.murad.myvpn.exception.VpnProviderPermanentException;
import ru.murad.myvpn.model.PaymentActivationStatus;
import ru.murad.myvpn.model.PaymentOrder;
import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.model.TelegramUser;
import ru.murad.myvpn.model.UserRole;
import ru.murad.myvpn.model.VpnTariff;
import ru.murad.myvpn.repository.PaymentOrderRepository;
import ru.murad.myvpn.repository.AccountRepository;
import ru.murad.myvpn.repository.SubscriptionRepository;
import ru.murad.myvpn.repository.TelegramUserRepository;
import ru.murad.myvpn.repository.VpnAccessRepository;
import ru.murad.myvpn.repository.VpnTariffRepository;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "payment.activation.enabled=false",
        "vpn.lifecycle.expiration-check-delay=3600000",
        "vpn.lifecycle.pending-recovery-delay=3600000"
})
@ActiveProfiles("test")
@Testcontainers
class PaymentActivationSensitiveFailureIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.3-alpine");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private PaymentActivationService activationService;
    @Autowired private PaymentOrderRepository orders;
    @Autowired private SubscriptionRepository subscriptions;
    @Autowired private VpnAccessRepository accesses;
    @Autowired private TelegramUserRepository users;
    @Autowired private AccountRepository accounts;
    @Autowired private VpnTariffRepository tariffs;
    @Autowired private EntityManager entityManager;
    @MockitoBean private VpnProvider vpnProvider;

    @BeforeEach
    void clean() {
        orders.deleteAll();
        accesses.deleteAll();
        subscriptions.deleteAll();
        users.deleteAll();
        accounts.deleteAll();
        tariffs.deleteAll();
        when(vpnProvider.providerName()).thenReturn("FAKE");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SECRET_TOKEN",
            "DATABASE_PASSWORD",
            "https://user:password@example.test",
            "external-client-id-123",
            "Bearer abcdef"
    })
    void permanentProviderRawMessageIsNeverPersisted(String rawMessage) {
        PaymentOrder order = succeededOrder();
        when(vpnProvider.provision(any())).thenThrow(new VpnProviderPermanentException(rawMessage));

        PaymentActivationWorkerResult workerResult = activationService.processPendingActivations(20);
        orders.flush();
        entityManager.clear();
        PaymentOrder reread = orders.findById(order.getId()).orElseThrow();

        assertThat(reread.getActivationStatus()).isEqualTo(PaymentActivationStatus.MANUAL_REVIEW_REQUIRED);
        assertThat(reread.getSafeFailureCode()).isEqualTo(PaymentActivationFailureCode.VPN_PROVIDER_PERMANENT.name());
        assertThat(reread.getSafeFailureCode()).isNotEqualTo(rawMessage);
        assertThat(workerResult.toString()).doesNotContain(rawMessage, reread.getSafeFailureCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SECRET_TOKEN", "https://user:password@example.test"})
    void unexpectedProviderRuntimeUsesFixedSafeFailureCode(String rawMessage) {
        PaymentOrder order = succeededOrder();
        when(vpnProvider.provision(any())).thenThrow(new IllegalStateException(rawMessage));

        PaymentActivationWorkerResult workerResult = activationService.processPendingActivations(20);
        orders.flush();
        entityManager.clear();
        PaymentOrder reread = orders.findById(order.getId()).orElseThrow();

        assertThat(reread.getActivationStatus()).isEqualTo(PaymentActivationStatus.RETRY_REQUIRED);
        assertThat(reread.getSafeFailureCode()).isEqualTo(PaymentActivationFailureCode.ACTIVATION_PROVIDER_UNEXPECTED.name());
        assertThat(reread.getSafeFailureCode()).isNotEqualTo(rawMessage);
        assertThat(workerResult.toString()).doesNotContain(rawMessage, reread.getSafeFailureCode());
    }

    private PaymentOrder succeededOrder() {
        long telegramId = Math.abs(UUID.randomUUID().getLeastSignificantBits());
        TelegramUser user = ru.murad.myvpn.support.AccountTestData.saveTelegramUser(
                accounts, users, TelegramUser.builder().id(UUID.randomUUID()).telegramId(telegramId)
                        .chatId(telegramId).role(UserRole.USER).createdAt(NOW).updatedAt(NOW).build());
        VpnTariff tariff = tariffs.save(VpnTariff.builder().id(UUID.randomUUID()).code("SAFE_" + telegramId)
                .name("Safe").durationDays(30).price(new BigDecimal("90.00")).currency("RUB")
                .active(true).createdAt(NOW).updatedAt(NOW).build());
        PaymentOrder order = PaymentOrder.create(user, tariff, PaymentProviderType.FAKE, NOW, Duration.ofHours(1));
        order.markCreating(NOW.plusSeconds(1));
        order.markPending("safe-" + telegramId, "https://example.invalid", NOW.plusSeconds(2), null, NOW.plusSeconds(3));
        order.markSucceeded(NOW.plusSeconds(4), NOW.plusSeconds(4));
        return orders.saveAndFlush(order);
    }
}
