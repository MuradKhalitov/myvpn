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
import org.testcontainers.containers.PostgreSQLContainer;
import ru.murad.myvpn.model.VpnTariff;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
                                "MONTH_1", 30, "90.00", "RUB"),
                        org.assertj.core.groups.Tuple.tuple(
                                "MONTH_3", 90, "240.00", "RUB"),
                        org.assertj.core.groups.Tuple.tuple(
                                "MONTH_6", 180, "420.00", "RUB"),
                        org.assertj.core.groups.Tuple.tuple(
                                "YEAR_1", 365, "720.00", "RUB")
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
