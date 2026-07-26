package ru.murad.myvpn.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.murad.myvpn.client.VpnProvider;
import ru.murad.myvpn.model.PaymentActivationStatus;
import ru.murad.myvpn.model.PaymentOrder;
import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.model.TelegramUser;
import ru.murad.myvpn.model.UserRole;
import ru.murad.myvpn.model.VpnTariff;
import ru.murad.myvpn.repository.PaymentOrderRepository;
import ru.murad.myvpn.repository.SubscriptionRepository;
import ru.murad.myvpn.repository.TelegramUserRepository;
import ru.murad.myvpn.repository.VpnAccessRepository;
import ru.murad.myvpn.repository.VpnTariffRepository;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "payment.activation.enabled=false",
        "vpn.lifecycle.expiration-check-delay=3600000",
        "vpn.lifecycle.pending-recovery-delay=3600000"
})
@ActiveProfiles("test")
@Testcontainers
class PaymentActivationTransactionBoundaryIntegrationTest {
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
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private PaymentOrderRepository orders;
    @Autowired private SubscriptionRepository subscriptions;
    @Autowired private VpnAccessRepository accesses;
    @Autowired private TelegramUserRepository users;
    @Autowired private VpnTariffRepository tariffs;
    @MockitoBean private VpnProvider vpnProvider;

    @BeforeEach
    void clean() {
        orders.deleteAll();
        accesses.deleteAll();
        subscriptions.deleteAll();
        users.deleteAll();
        tariffs.deleteAll();
        when(vpnProvider.providerName()).thenReturn("FAKE");
    }

    @Test
    void realSpringTransactionFailsBeforeProviderInvocationAndRollsBackClaim() {
        PaymentOrder order = succeededOrder();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                activationService.processPendingActivations(20)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside transaction");

        verify(vpnProvider, never()).provision(any());
        verify(vpnProvider, never()).extend(any());
        PaymentOrder reread = orders.findById(order.getId()).orElseThrow();
        assertThat(reread.getActivationStatus()).isEqualTo(PaymentActivationStatus.PENDING);
        assertThat(reread.getSafeFailureCode()).isNull();
    }

    private PaymentOrder succeededOrder() {
        TelegramUser user = users.save(TelegramUser.builder().id(UUID.randomUUID()).telegramId(88001L)
                .chatId(88001L).role(UserRole.USER).createdAt(NOW).updatedAt(NOW).build());
        VpnTariff tariff = tariffs.save(VpnTariff.builder().id(UUID.randomUUID()).code("BOUNDARY")
                .name("Boundary").durationDays(30).price(new BigDecimal("90.00")).currency("RUB")
                .active(true).createdAt(NOW).updatedAt(NOW).build());
        PaymentOrder order = PaymentOrder.create(user, tariff, PaymentProviderType.FAKE, NOW, Duration.ofHours(1));
        order.markCreating(NOW.plusSeconds(1));
        order.markPending("boundary", "https://example.invalid", NOW.plusSeconds(2), null, NOW.plusSeconds(3));
        order.markSucceeded(NOW.plusSeconds(4), NOW.plusSeconds(4));
        return orders.saveAndFlush(order);
    }
}
