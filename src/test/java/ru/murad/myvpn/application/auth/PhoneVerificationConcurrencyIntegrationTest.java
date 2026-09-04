package ru.murad.myvpn.application.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.http.MediaType;
import ru.murad.myvpn.client.PhoneVerificationProvider;
import ru.murad.myvpn.client.PhoneVerificationStart;
import ru.murad.myvpn.client.PhoneVerificationState;
import ru.murad.myvpn.dto.PhoneAuthResponse;
import ru.murad.myvpn.dto.PhoneVerificationStartResponse;
import ru.murad.myvpn.dto.PhoneVerificationStatusResponse;
import ru.murad.myvpn.model.AccountIdentityType;
import ru.murad.myvpn.support.AuthIntegrationTestSupport;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class PhoneVerificationConcurrencyIntegrationTest extends AuthIntegrationTestSupport {
    @MockBean PhoneVerificationProvider phoneProvider;
    @Autowired PhoneVerificationService phoneService;

    @DynamicPropertySource
    static void phoneProperties(DynamicPropertyRegistry registry) {
        registry.add("sms.ru.enabled", () -> "true");
        registry.add("sms.ru.api-id", () -> "test-api-id");
        registry.add("sms.ru.base-url", () -> "https://sms.ru");
    }

    @Test
    void concurrentVerifiedPollAndExchangeCreateOneAccountTrialIdentityAccessAndSession() {
        PhoneVerificationStartResponse start = start("check-race");
        when(phoneProvider.getStatus("check-race")).thenReturn(PhoneVerificationState.VERIFIED);

        List<PhoneVerificationStatusResponse> results = parallel(() -> phoneService.status(start.verificationId()));
        String exchange = results.stream().map(PhoneVerificationStatusResponse::exchangeToken)
                .filter(value -> value != null).findFirst().orElseThrow();
        assertThat(results).extracting(PhoneVerificationStatusResponse::status).containsOnly("VERIFIED");
        assertThat(results.stream().filter(value -> value.exchangeToken() != null)).hasSize(1);

        List<Throwable> failures = parallelCapture(() -> phoneService.exchange(start.verificationId(), exchange));
        assertThat(failures).hasSize(1);
        assertThat(accountRepository.count()).isEqualTo(1);
        assertThat(identityRepository.findByTypeAndNormalizedSubject(AccountIdentityType.PHONE, "+79991234567")).isPresent();
        assertThat(identityRepository.count()).isEqualTo(1);
        assertThat(vpnAccessRepository.count()).isEqualTo(1);
        assertThat(sessionRepository.count()).isEqualTo(1);
        assertThat(accountRepository.findAll().get(0).getTrialGrantedAt()).isNotNull();

        PhoneVerificationStatusResponse repeated = phoneService.status(start.verificationId());
        assertThat(repeated.status()).isEqualTo("VERIFIED");
        assertThat(repeated.exchangeToken()).isNull();
        assertThat(sessionRepository.count()).isEqualTo(1);
    }

    @Test
    void oneTimeExchangeRejectsInvalidExpiredAndRepeatedTokens() {
        PhoneVerificationStartResponse start = start("check-once");
        when(phoneProvider.getStatus("check-once")).thenReturn(PhoneVerificationState.VERIFIED);
        String exchange = phoneService.status(start.verificationId()).exchangeToken();

        assertThat(phoneService.exchange(start.verificationId(), exchange).accessToken()).isNotBlank();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> phoneService.exchange(start.verificationId(), exchange))
                .isInstanceOf(RuntimeException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> phoneService.exchange(start.verificationId(), "invalid"))
                .isInstanceOf(RuntimeException.class);
        assertThat(sessionRepository.count()).isEqualTo(1);
    }

    @Test
    void phoneEndpointsArePublicWhileOtherV1EndpointsRequireBearer() {
        when(phoneProvider.start(anyString())).thenReturn(new PhoneVerificationStart("check-http", "7800", "+7 800",
                Instant.now().plusSeconds(300)));
        webTestClient.post().uri("/api/v1/auth/phone/start").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"phone\":\"+79991234567\"}").exchange().expectStatus().isOk();
        webTestClient.get().uri("/api/v1/auth/phone/00000000-0000-0000-0000-000000000001/status")
                .exchange().expectStatus().isUnauthorized();
        webTestClient.post().uri("/api/v1/auth/phone/exchange").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"verificationId\":\"00000000-0000-0000-0000-000000000001\",\"exchangeToken\":\"x\"}")
                .exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri("/api/v1/protected-resource").exchange().expectStatus().isUnauthorized()
                .expectHeader().value("WWW-Authenticate", value -> assertThat(value).startsWith("Bearer").doesNotContain("Basic"));
    }

    private PhoneVerificationStartResponse start(String checkId) {
        when(phoneProvider.start(anyString())).thenReturn(new PhoneVerificationStart(checkId, "7800", "+7 800",
                Instant.now().plusSeconds(300)));
        return phoneService.start("8 (999) 123-45-67", "127.0.0.1");
    }

    private static List<PhoneVerificationStatusResponse> parallel(java.util.concurrent.Callable<PhoneVerificationStatusResponse> action) {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            return List.of(CompletableFuture.supplyAsync(() -> call(action), executor),
                    CompletableFuture.supplyAsync(() -> call(action), executor)).stream().map(CompletableFuture::join).toList();
        } finally { executor.shutdownNow(); }
    }
    private static List<Throwable> parallelCapture(java.util.concurrent.Callable<PhoneAuthResponse> action) {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            return List.of(CompletableFuture.supplyAsync(() -> { try { action.call(); return null; } catch (Throwable t) { return t; } }, executor),
                    CompletableFuture.supplyAsync(() -> { try { action.call(); return null; } catch (Throwable t) { return t; } }, executor))
                    .stream().map(CompletableFuture::join).filter(value -> value != null).toList();
        } finally { executor.shutdownNow(); }
    }
    private static <T> T call(java.util.concurrent.Callable<T> action) { try { return action.call(); } catch (Exception e) { throw new RuntimeException(e); } }
}
