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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class VpnAccessAccountOwnershipMigrationIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.3-alpine");

    @Test
    void backfillIsLosslessAndAllowsHistoricalAccessesForOneAccount() throws Exception {
        UUID accountId = UUID.randomUUID(); UUID tariffId = UUID.randomUUID();
        UUID firstSubscription = UUID.randomUUID(); UUID secondSubscription = UUID.randomUUID();
        UUID firstAccess = UUID.randomUUID(); UUID secondAccess = UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Liquibase liquibase = new Liquibase("db/changelog/db.changelog-master.yaml", new ClassLoaderResourceAccessor(), new JdbcConnection(connection))) {
            liquibase.update(12, new Contexts(), new LabelExpression());
            seedUser(connection, accountId); seedTariff(connection, tariffId);
            seedSubscription(connection, firstSubscription, accountId, tariffId, 1);
            seedSubscription(connection, secondSubscription, accountId, tariffId, 2);
            seedAccess(connection, firstAccess, firstSubscription, "legacy-one", "configuration-one");
            seedAccess(connection, secondAccess, secondSubscription, "legacy-two", "configuration-two");
            connection.commit();

            liquibase.update(3, new Contexts(), new LabelExpression());
            assertAccess(connection, firstAccess, accountId, firstSubscription, "legacy-one", "configuration-one");
            assertAccess(connection, secondAccess, accountId, secondSubscription, "legacy-two", "configuration-two");
            assertThat(count(connection, "select count(*) from vpn_accesses where account_id='" + accountId + "'" )).isEqualTo(2);
        }
    }

    private void seedUser(Connection c, UUID id) throws Exception { execute(c, "insert into telegram_users (id,telegram_id,chat_id,role,created_at,updated_at) values ('"+id+"',9001,9001,'USER',now(),now())"); }
    private void seedTariff(Connection c, UUID id) throws Exception { execute(c, "insert into vpn_tariffs (id,code,name,duration_days,price,currency,active,created_at,updated_at) values ('"+id+"','MIGRATE','Migrate',30,1,'RUB',true,now(),now())"); }
    private void seedSubscription(Connection c, UUID id, UUID user, UUID tariff, int n) throws Exception { execute(c, "insert into subscriptions (id,user_id,tariff_id,status,starts_at,expires_at,activated_by_telegram_id,activated_at,created_at,updated_at) values ('"+id+"','"+user+"','"+tariff+"','EXPIRED',now(),now(),"+n+",now(),now(),now())"); }
    private void seedAccess(Connection c, UUID id, UUID subscription, String external, String configuration) throws Exception { try (PreparedStatement s=c.prepareStatement("insert into vpn_accesses (id,subscription_id,provider_name,external_access_id,configuration_data,status,issued_at,created_at,updated_at) values (?,?,?,?,?,'REVOKED',?,?,?)")) { s.setObject(1,id);s.setObject(2,subscription);s.setString(3,"FAKE");s.setString(4,external);s.setString(5,configuration);s.setTimestamp(6,Timestamp.from(Instant.EPOCH));s.setTimestamp(7,Timestamp.from(Instant.EPOCH));s.setTimestamp(8,Timestamp.from(Instant.EPOCH));s.executeUpdate(); } }
    private void assertAccess(Connection c, UUID id, UUID account, UUID subscription, String external, String config) throws Exception { try (PreparedStatement s=c.prepareStatement("select account_id,subscription_id,external_access_id,configuration_data,status from vpn_accesses where id=?")) { s.setObject(1,id); var r=s.executeQuery();assertThat(r.next()).isTrue();assertThat(r.getObject(1,UUID.class)).isEqualTo(account);assertThat(r.getObject(2,UUID.class)).isEqualTo(subscription);assertThat(r.getString(3)).isEqualTo(external);assertThat(r.getString(4)).isEqualTo(config);assertThat(r.getString(5)).isEqualTo("REVOKED"); } }
    private void execute(Connection c,String sql) throws Exception { try(var s=c.createStatement()){s.execute(sql);} }
    private long count(Connection c,String sql) throws Exception { try(var s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getLong(1);} }
}
