package ru.murad.myvpn.service;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "payment.activation.enabled=false")
@ActiveProfiles("test")
@Testcontainers
class PaymentActivationMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.3-alpine");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void migrationAddsTheActivationQueueColumnAndIndex() throws Exception {
        assertThat(columnExists("next_activation_at")).isTrue();
        assertThat(indexExists("idx_payment_orders_activation_queue")).isTrue();
    }

    @Test
    void migrationIsAdditiveAndExistingPaymentRowsRemainReadable() {
        assertThat(jdbc.queryForObject("select count(*) from payment_orders", Integer.class)).isNotNegative();
        assertThat(columnExists("next_activation_at")).isTrue();
    }

    @Test
    void migrationRollbackAndReapplyRestoreColumnAndIndex() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            Liquibase liquibase = new Liquibase("db/changelog/db.changelog-master.yaml",
                    new ClassLoaderResourceAccessor(), new JdbcConnection(connection));
            liquibase.rollback(3, new Contexts(), new LabelExpression());
            assertThat(columnExists("next_activation_at")).isFalse();
            assertThat(indexExists("idx_payment_orders_activation_queue")).isFalse();
            assertThat(columnExists("activation_completed_at")).isFalse();
            assertThat(columnExists("version", "vpn_accesses")).isFalse();
            assertThat(columnExists("id", "vpn_deliveries")).isFalse();
            liquibase.update(new Contexts(), new LabelExpression());
        }
        assertThat(columnExists("next_activation_at")).isTrue();
        assertThat(indexExists("idx_payment_orders_activation_queue")).isTrue();
        assertThat(columnExists("activation_completed_at")).isTrue();
        assertThat(columnExists("version", "vpn_accesses")).isTrue();
        assertThat(columnExists("id", "vpn_deliveries")).isTrue();
    }

    private boolean columnExists(String column) {
        return columnExists(column, "payment_orders");
    }

    private boolean columnExists(String column, String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from information_schema.columns where table_name=? and column_name=?)",
                Boolean.class, table, column));
    }

    private boolean indexExists(String index) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from pg_indexes where indexname=?)", Boolean.class, index));
    }
}
