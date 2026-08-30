package ru.murad.myvpn.service;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class AccountIdentityBackfillPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16.3-alpine");

    @Test
    void backfillsAccountsAndTelegramIdentitiesWithoutChangingUserUuids()
            throws Exception {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
             Liquibase liquibase = new Liquibase(
                     "db/changelog/db.changelog-master.yaml",
                     new ClassLoaderResourceAccessor(),
                     new JdbcConnection(connection))) {
            liquibase.update(12, new Contexts(), new LabelExpression());
            insertTelegramUser(connection, firstId, 91001L);
            insertTelegramUser(connection, secondId, 91002L);
            connection.commit();

            liquibase.update(1, new Contexts(), new LabelExpression());

            assertThat(count(connection, "select count(*) from telegram_users"))
                    .isEqualTo(2);
            assertThat(count(connection, "select count(*) from accounts"))
                    .isEqualTo(2);
            assertThat(count(connection, """
                    select count(*)
                    from telegram_users tu
                    left join accounts a on a.id = tu.id
                    where a.id is null
                    """)).isZero();
            assertThat(count(connection, """
                    select count(*)
                    from account_identities ai
                    left join accounts a on a.id = ai.account_id
                    where a.id is null
                    """)).isZero();
            assertThat(count(connection, """
                    select count(*)
                    from telegram_users tu
                    left join account_identities ai
                      on ai.account_id = tu.id and ai.type = 'TELEGRAM'
                    where ai.id is null
                    """)).isZero();
            assertThat(count(connection, """
                    select count(*) from (
                      select type, normalized_subject
                      from account_identities
                      group by type, normalized_subject
                      having count(*) > 1
                    ) duplicates
                    """)).isZero();
            assertThat(count(connection, """
                    select count(*) from (
                      select account_id, type
                      from account_identities
                      group by account_id, type
                      having count(*) > 1
                    ) duplicates
                    """)).isZero();
            assertIdentity(connection, firstId, 91001L);
            assertIdentity(connection, secondId, 91002L);
        }
    }

    private void insertTelegramUser(
            Connection connection,
            UUID id,
            long telegramId
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into telegram_users
                  (id, telegram_id, chat_id, role, created_at, updated_at)
                values (?, ?, ?, 'USER', ?, ?)
                """)) {
            Instant createdAt = Instant.parse("2026-08-01T10:00:00Z");
            statement.setObject(1, id);
            statement.setLong(2, telegramId);
            statement.setLong(3, telegramId);
            statement.setTimestamp(4, Timestamp.from(createdAt));
            statement.setTimestamp(5, Timestamp.from(createdAt));
            statement.executeUpdate();
        }
    }

    private void assertIdentity(Connection connection, UUID accountId, long telegramId)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                select a.id as account_id, ai.id as identity_id,
                       ai.subject, ai.normalized_subject,
                       ai.verified_at, tu.created_at
                from telegram_users tu
                join accounts a on a.id = tu.id
                join account_identities ai
                  on ai.account_id = a.id and ai.type = 'TELEGRAM'
                where tu.id = ?
                """)) {
            statement.setObject(1, accountId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getObject("account_id", UUID.class))
                        .isEqualTo(accountId);
                assertThat(result.getObject("identity_id", UUID.class))
                        .isNotEqualTo(accountId);
                assertThat(result.getString("subject"))
                        .isEqualTo(Long.toString(telegramId));
                assertThat(result.getString("normalized_subject"))
                        .isEqualTo(Long.toString(telegramId));
                assertThat(result.getTimestamp("verified_at"))
                        .isEqualTo(result.getTimestamp("created_at"));
                assertThat(result.next()).isFalse();
            }
        }
    }

    private long count(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }
}
