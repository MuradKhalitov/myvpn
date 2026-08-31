package ru.murad.myvpn.application.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import ru.murad.myvpn.model.AccountIdentityType;
import ru.murad.myvpn.service.AccountVpnAccessService;
import ru.murad.myvpn.support.AuthIntegrationTestSupport;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceRegistrationConcurrencyIntegrationTest extends AuthIntegrationTestSupport {

    private static final String INSTALL_ID = "8b60d9a2-59ed-42a8-a0cb-6b95797a5732";
    private static final String SECRET = "A".repeat(43);

    @Autowired private DeviceRegistrationService registrationService;
    @MockBean private AccountVpnAccessService vpnAccessService;

    @Test
    void concurrentFirstRegistrationCreatesOneAccountIdentityAndCredential() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<DeviceRegistrationResult> first = CompletableFuture.supplyAsync(
                    () -> registrationService.register(INSTALL_ID, SECRET), executor);
            CompletableFuture<DeviceRegistrationResult> second = CompletableFuture.supplyAsync(
                    () -> registrationService.register(INSTALL_ID, SECRET), executor);

            assertThat(first.join().accountId()).isEqualTo(second.join().accountId());
        } finally {
            executor.shutdownNow();
        }

        assertThat(accountRepository.count()).isEqualTo(1);
        assertThat(identityRepository.findByTypeAndNormalizedSubject(AccountIdentityType.DEVICE, INSTALL_ID))
                .isPresent();
        assertThat(deviceCredentialRepository.count()).isEqualTo(1);
        assertThat(sessionRepository.count()).isEqualTo(2);
    }
}
