package ru.murad.myvpn;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class MyVpnApplicationIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    @Container
    static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer<>("postgres:16.3-alpine");

    @DynamicPropertySource
    static void configurePostgresql(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
    }

    @Test
    void contextLoads() {
    }

    @Test
    void cleanBaselineCreatesAndroidFirstSchema() {
        for (String table : new String[]{"accounts", "account_identities", "device_credentials",
                "email_otp_challenges", "auth_sessions", "vpn_tariffs", "subscriptions",
                "payment_orders", "vpn_accesses"}) {
            org.assertj.core.api.Assertions.assertThat(tableExists(table)).isTrue();
        }
        org.assertj.core.api.Assertions.assertThat(tableExists("telegram_users")).isFalse();
        org.assertj.core.api.Assertions.assertThat(tableExists("vpn_deliveries")).isFalse();
        org.assertj.core.api.Assertions.assertThat(columnExists("subscriptions", "account_id")).isTrue();
        org.assertj.core.api.Assertions.assertThat(columnExists("subscriptions", "user_id")).isFalse();
        org.assertj.core.api.Assertions.assertThat(columnExists("payment_orders", "account_id")).isTrue();
        org.assertj.core.api.Assertions.assertThat(columnExists("payment_orders", "user_id")).isFalse();
    }

    private boolean tableExists(String table) {
        return jdbc.queryForObject("select count(*) from information_schema.tables where table_schema='public' and table_name=?", Integer.class, table) == 1;
    }

    private boolean columnExists(String table, String column) {
        return jdbc.queryForObject("select count(*) from information_schema.columns where table_schema='public' and table_name=? and column_name=?", Integer.class, table, column) == 1;
    }
}
