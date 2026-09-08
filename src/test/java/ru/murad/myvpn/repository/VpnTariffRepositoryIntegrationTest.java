package ru.murad.myvpn.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.PostgreSQLContainer;
import ru.murad.myvpn.model.VpnTariff;
import ru.murad.myvpn.model.Account;
import ru.murad.myvpn.model.AccountStatus;
import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.model.ProviderPaymentStatus;
import ru.murad.myvpn.model.SubscriptionStatus;
import ru.murad.myvpn.client.PaymentProvider;
import ru.murad.myvpn.client.VpnProvider;
import ru.murad.myvpn.client.ProvisionedVpnAccess;
import ru.murad.myvpn.config.PaymentProperties;
import ru.murad.myvpn.controller.TariffController;
import ru.murad.myvpn.dto.CreatedPayment;
import ru.murad.myvpn.mapper.VpnTariffMapper;
import ru.murad.myvpn.service.CreatedPaymentValidator;
import ru.murad.myvpn.service.PaymentProviderRegistry;
import ru.murad.myvpn.service.PaymentActivationTransactionService.PaymentActivationOutcome;
import ru.murad.myvpn.service.impl.PaymentOrderCreationServiceImpl;
import ru.murad.myvpn.service.impl.PaymentCheckoutTransactionServiceImpl;
import ru.murad.myvpn.service.impl.PaymentCheckoutServiceImpl;
import ru.murad.myvpn.service.impl.PaymentActivationTransactionServiceImpl;
import ru.murad.myvpn.service.impl.TariffServiceImpl;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class VpnTariffRepositoryIntegrationTest {

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
    private VpnTariffRepository repository;

    @Autowired private AccountRepository accounts;
    @Autowired private PaymentOrderRepository orders;
    @Autowired private SubscriptionRepository subscriptions;
    @Autowired private VpnAccessRepository accesses;

    @Test
    void tariffEndpointReturnsMigratedBetaPricesAndUnchangedDurations() {
        var service = new TariffServiceImpl(repository, Mappers.getMapper(VpnTariffMapper.class));
        WebTestClient.bindToController(new TariffController(service)).build()
                .get().uri("/api/v1/tariffs").exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$[*].code").isEqualTo(java.util.List.of("MONTH_1", "MONTH_3", "MONTH_6", "YEAR_1"))
                .jsonPath("$[*].price").isEqualTo(java.util.List.of(10.0, 20.0, 30.0, 50.0))
                .jsonPath("$[*].durationDays").isEqualTo(java.util.List.of(30, 90, 180, 365))
                .jsonPath("$[*].currency").isEqualTo(java.util.List.of("RUB", "RUB", "RUB", "RUB"));
    }

    @ParameterizedTest
    @CsvSource({"MONTH_1,10.00,30", "MONTH_3,20.00,90", "MONTH_6,30.00,180", "YEAR_1,50.00,365"})
    void betaCheckoutAndSuccessfulPaymentKeepTariffDuration(String code, String price, int days) {
        Instant now = Instant.parse("2026-09-08T10:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        var account = accounts.saveAndFlush(Account.builder().id(UUID.randomUUID())
                .status(AccountStatus.ACTIVE).createdAt(now).updatedAt(now).build());
        var properties = new PaymentProperties(PaymentProviderType.YOOKASSA, Duration.ofHours(1),
                URI.create("https://example.test/return"), false);
        var provider = mock(PaymentProvider.class);
        var registry = mock(PaymentProviderRegistry.class);
        when(registry.resolve(PaymentProviderType.YOOKASSA)).thenReturn(provider);
        when(provider.createPayment(any())).thenAnswer(invocation -> new CreatedPayment(
                UUID.randomUUID().toString(), ProviderPaymentStatus.PENDING,
                URI.create("https://yookassa.test/confirm"), now, now.plusSeconds(3600)));
        var creation = new PaymentOrderCreationServiceImpl(accounts, repository, orders, clock);
        var transactions = new PaymentCheckoutTransactionServiceImpl(repository, orders, creation,
                new CreatedPaymentValidator());
        var checkout = new PaymentCheckoutServiceImpl(orders, registry, transactions, properties, clock);

        var result = checkout.startCheckout(account.getId(), code);

        assertThat(result.amount()).isEqualByComparingTo(price);
        assertThat(result.currency()).isEqualTo("RUB");
        verify(provider).createPayment(argThat(command -> command.amount().toPlainString().equals(price)
                && command.currency().equals("RUB")));
        var order = orders.findOpenByAccount(account.getId()).orElseThrow();
        assertThat(order.getDurationDaysSnapshot()).isEqualTo(days);
        // Same confirmed-payment transition used after provider verification of a webhook.
        order.markSucceeded(now, now);
        orders.saveAndFlush(order);
        var vpnProvider = mock(VpnProvider.class);
        when(vpnProvider.providerName()).thenReturn("test");
        var activation = new PaymentActivationTransactionServiceImpl(orders, subscriptions, accesses,
                repository, properties, vpnProvider);
        var prepared = activation.claimActivations(now, 10).get(0);
        assertThat(prepared.targetExpiresAt()).isEqualTo(now.plus(Duration.ofDays(days)));
        assertThat(activation.complete(prepared, new ProvisionedVpnAccess("test", account.getId().toString(),
                "test-configuration", prepared.targetExpiresAt()), now)).isEqualTo(PaymentActivationOutcome.SUCCEEDED);
        var subscription = subscriptions.findAllByAccountIdAndStatus(account.getId(), SubscriptionStatus.ACTIVE)
                .get(0);
        assertThat(subscription.getExpiresAt()).isEqualTo(now.plus(Duration.ofDays(days)));
        assertThat(subscription.getTariff().getCode()).isEqualTo(code);
    }

    @Test
    void shouldReturnOnlyActiveTariffsOrderedByPriceAndDuration() {
        repository.deleteAll();
        repository.save(createTariff("YEAR", 365, "3000.00", true));
        repository.save(createTariff("MONTH", 30, "300.00", true));
        repository.save(createTariff("QUARTER", 90, "300.00", true));
        repository.save(createTariff("ARCHIVED", 7, "100.00", false));
        repository.flush();

        assertThat(repository.findAllByActiveTrueOrderByPriceAscDurationDaysAsc())
                .extracting(VpnTariff::getCode)
                .containsExactly("MONTH", "QUARTER", "YEAR");
    }

    @Test
    void shouldFindActiveTariffByCode() {
        repository.deleteAll();
        repository.saveAndFlush(createTariff("MONTH", 30, "300.00", true));

        assertThat(repository.findByCodeAndActiveTrue("MONTH")).isPresent();
        assertThat(repository.findByCodeAndActiveTrue("UNKNOWN")).isEmpty();
    }

    @Test
    void shouldContainConfiguredInitialTariffs() {
        assertThat(repository.findAllByActiveTrueOrderByPriceAscDurationDaysAsc())
                .extracting(
                        VpnTariff::getCode,
                        VpnTariff::getDurationDays,
                        tariff -> tariff.getPrice().toPlainString(),
                        VpnTariff::getCurrency
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "MONTH_1", 30, "10.00", "RUB"),
                        org.assertj.core.groups.Tuple.tuple(
                                "MONTH_3", 90, "20.00", "RUB"),
                        org.assertj.core.groups.Tuple.tuple(
                                "MONTH_6", 180, "30.00", "RUB"),
                        org.assertj.core.groups.Tuple.tuple(
                                "YEAR_1", 365, "50.00", "RUB")
                );
    }

    private VpnTariff createTariff(
            String code,
            int durationDays,
            String price,
            boolean active
    ) {
        Instant now = Instant.parse("2026-07-24T10:00:00Z");
        return VpnTariff.builder()
                .id(UUID.randomUUID())
                .code(code)
                .name(code + " tariff")
                .description("Test tariff")
                .durationDays(durationDays)
                .price(new BigDecimal(price))
                .currency("RUB")
                .active(active)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
