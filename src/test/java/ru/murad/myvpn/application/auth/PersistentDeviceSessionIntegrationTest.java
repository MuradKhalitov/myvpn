package ru.murad.myvpn.application.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.murad.myvpn.config.AuthProperties;
import ru.murad.myvpn.model.Account;
import ru.murad.myvpn.model.AccountStatus;
import ru.murad.myvpn.model.AuthSession;
import ru.murad.myvpn.support.AuthIntegrationTestSupport;

import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersistentDeviceSessionIntegrationTest extends AuthIntegrationTestSupport {
    @Autowired private AuthProperties properties;
    @Autowired private JwtTokenService jwt;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @ParameterizedTest
    @ValueSource(longs = {0, 10, 600, 86400, 2592000, 3153600000L})
    void lostResponseRecoversUntilCurrentGenerationIsUsed(long delaySeconds) {
        UUID id = seed();
        Instant issued = Instant.now();
        String b = refresh(newService(issued), "initial-device-credential");
        // Simulate a migrated row retaining an already expired legacy deadline.
        jdbc.update("update auth_sessions set previous_refresh_valid_until = ? where id = ?",
                java.sql.Timestamp.from(issued.minusSeconds(300)), id);
        String recovered = refresh(newService(issued.plusSeconds(delaySeconds)), "initial-device-credential");
        assertThat(recovered).isEqualTo(b);
        var saved = sessionRepository.findById(id).orElseThrow();
        var hash = new RefreshTokenHashService(properties.refresh().pepper());
        assertThat(saved.getRotationCounter()).isEqualTo(1);
        assertThat(saved.getRefreshTokenHash()).isEqualTo(hash.hash(b)).isNotEqualTo(b);
        assertThat(saved.getPreviousRefreshTokenHash()).isEqualTo(hash.hash("initial-device-credential"))
                .isNotEqualTo("initial-device-credential");
        assertThat(saved.getExpiresAt()).isNull();

        String c = refresh(newService(issued.plusSeconds(delaySeconds)), b);
        assertThat(c).isNotEqualTo(b);
        assertThat(refresh(newService(issued.plusSeconds(delaySeconds + 86400)), b)).isEqualTo(c);
        assertThatThrownBy(() -> refresh(newService(), "initial-device-credential"))
                .isInstanceOf(RefreshAuthenticationException.class)
                .extracting("reason").isEqualTo(RefreshAuthenticationException.Reason.REFRESH_TOKEN_INVALID);
        var next = sessionRepository.findById(id).orElseThrow();
        assertThat(next.getRotationCounter()).isEqualTo(2);
        assertThat(next.getPreviousRefreshTokenHash()).isEqualTo(hash.hash(b));
        assertThat(next.getRefreshTokenHash()).isEqualTo(hash.hash(c));
        assertThat(next.getPreviousRefreshValidUntil()).isNull();
    }

    @Test void recreatedServicesRestoreOldDatabaseSessionAndLostResponse() {
        UUID id = seed();
        String rotated = refresh(newService(), "initial-device-credential");
        // A fresh service and HMAC instance have no process-local rotation state.
        assertThat(refresh(newService(), "initial-device-credential")).isEqualTo(rotated);
        assertThat(sessionRepository.findById(id).orElseThrow().getRotationCounter()).isEqualTo(1);
        assertThat(refresh(newService(), rotated)).isNotEqualTo(rotated);
        assertThat(sessionRepository.findById(id).orElseThrow().getExpiresAt()).isNull();
    }

    @Test void independentInstancesUsePostgresLockingForOneRotation() throws Exception {
        UUID id = seed();
        String b = concurrentRefresh("initial-device-credential");
        assertThat(sessionRepository.findById(id).orElseThrow().getRotationCounter()).isEqualTo(1);
        String c = concurrentRefresh(b);
        assertThat(c).isNotEqualTo(b);
        assertThat(sessionRepository.findById(id).orElseThrow().getRotationCounter()).isEqualTo(2);
    }

    private String concurrentRefresh(String credential) throws Exception {
        var firstService = newService();
        var secondService = newService();
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = CompletableFuture.supplyAsync(() -> afterStart(start, firstService, credential), executor);
            var second = CompletableFuture.supplyAsync(() -> afterStart(start, secondService, credential), executor);
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(second.get(20, TimeUnit.SECONDS));
            return first.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test void revokeImmediatelyRejectsBothCurrentAndPreviousGenerations() {
        UUID id = seed();
        String b = refresh(newService(), "initial-device-credential");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var session = sessionRepository.findByIdForUpdate(id).orElseThrow();
            session.revoke(Instant.now());
        });
        assertThatThrownBy(() -> refresh(newService(), b))
                .isInstanceOf(RefreshAuthenticationException.class)
                .extracting("reason").isEqualTo(RefreshAuthenticationException.Reason.SESSION_REVOKED);
        assertThatThrownBy(() -> refresh(newService(), "initial-device-credential"))
                .isInstanceOf(RefreshAuthenticationException.class)
                .extracting("reason").isEqualTo(RefreshAuthenticationException.Reason.SESSION_REVOKED);
    }

    private UUID seed() {
        Instant old = Instant.now().minus(Duration.ofDays(2000));
        var account = accountRepository.saveAndFlush(Account.builder().id(UUID.randomUUID())
                .status(AccountStatus.ACTIVE).createdAt(old).updatedAt(old).build());
        return sessionRepository.saveAndFlush(AuthSession.builder().id(UUID.randomUUID()).account(account)
                .refreshTokenHash(new RefreshTokenHashService(properties.refresh().pepper()).hash("initial-device-credential"))
                .tokenFamilyId(UUID.randomUUID()).createdAt(old).expiresAt(old.plusSeconds(3600)).build()).getId();
    }

    private RefreshTokenService newService() {
        return newService(Instant.now());
    }

    private RefreshTokenService newService(Instant now) {
        return new RefreshTokenService(sessionRepository, new RefreshTokenHashService(properties.refresh().pepper()),
                jwt, properties, Clock.fixed(now, ZoneOffset.UTC));
    }

    private String refresh(RefreshTokenService service, String credential) {
        return new TransactionTemplate(transactionManager).execute(status -> service.refresh(credential).getRefreshToken());
    }

    private String afterStart(CountDownLatch start, RefreshTokenService service, String credential) {
        try {
            if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timed out");
            return refresh(service, credential);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
