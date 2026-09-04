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
import ru.murad.myvpn.model.PhoneVerification;
import ru.murad.myvpn.model.PhoneVerificationStatus;
import ru.murad.myvpn.support.AuthIntegrationTestSupport;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
    void repeatedStartForPendingPhoneReturnsOriginalSessionWithoutSecondProviderCall() {
        PhoneVerificationStartResponse first = start("check-idempotent");

        PhoneVerificationStartResponse repeated = phoneService.start("+7 999 123-45-67", "127.0.0.1");

        assertThat(repeated.verificationId()).isEqualTo(first.verificationId());
        assertThat(repeated.callPhone()).isEqualTo(first.callPhone());
        assertThat(repeated.callPhonePretty()).isEqualTo(first.callPhonePretty());
        verify(phoneProvider, times(1)).start("+79991234567");
    }

    @Test
    void returningToAnEarlierPendingPhoneRecoversItsOriginalSession() {
        when(phoneProvider.start(anyString())).thenAnswer(invocation -> {
            String phone = invocation.getArgument(0);
            return new PhoneVerificationStart("check-" + phone, "7800", "+7 800", Instant.now().plusSeconds(300));
        });
        PhoneVerificationStartResponse a = phoneService.start("+7 963 375-10-02", "127.0.0.1");
        phoneService.start("+7 963 375-10-01", "127.0.0.1");

        PhoneVerificationStartResponse recovered = phoneService.start("89633751002", "127.0.0.1");

        assertThat(recovered.verificationId()).isEqualTo(a.verificationId());
        assertThat(recovered.callPhone()).isEqualTo(a.callPhone());
        verify(phoneProvider, times(2)).start(anyString());
    }

    @Test
    void expiredVerificationAllowsANewProviderStartWhenCooldownAllowsIt() {
        String phone = "+79991234567";
        Instant createdAt = Instant.now().minusSeconds(120);
        phoneVerificationRepository.save(PhoneVerification.builder().id(UUID.randomUUID()).phone(phone).requestIp("127.0.0.1")
                .provider("SMS_RU").providerCheckId("expired-check").callPhone("7800").callPhonePretty("+7 800")
                .status(PhoneVerificationStatus.EXPIRED).createdAt(createdAt).expiresAt(createdAt.plusSeconds(30)).build());
        when(phoneProvider.start(phone)).thenReturn(new PhoneVerificationStart("fresh-check", "7900", "+7 900", Instant.now().plusSeconds(300)));

        PhoneVerificationStartResponse fresh = phoneService.start(phone, "127.0.0.1");

        assertThat(fresh.callPhone()).isEqualTo("7900");
        verify(phoneProvider).start(phone);
    }

    @Test
    void concurrentStartsForOnePhoneCreateOneProviderVerificationAndReturnOneSession() {
        when(phoneProvider.start(anyString())).thenReturn(new PhoneVerificationStart("check-concurrent-start", "7800", "+7 800", Instant.now().plusSeconds(300)));

        List<PhoneVerificationStartResponse> starts = parallelStarts(() -> phoneService.start("+79991234567", "127.0.0.1"));

        assertThat(starts).extracting(PhoneVerificationStartResponse::verificationId).containsOnly(starts.get(0).verificationId());
        verify(phoneProvider, times(1)).start("+79991234567");
    }

    @Test
    void rateLimitStillAppliesToNewVerificationsButNotPendingReuse() {
        when(phoneProvider.start(anyString())).thenAnswer(invocation -> {
            String phone = invocation.getArgument(0);
            return new PhoneVerificationStart("check-" + phone, "7800", "+7 800", Instant.now().plusSeconds(300));
        });
        phoneService.start("+79991234567", "127.0.0.1");
        phoneService.start("+79991234567", "127.0.0.1");
        for (int value = 0; value < 9; value++) phoneService.start(String.format("+799912345%02d", value), "127.0.0.1");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> phoneService.start("+79991234509", "127.0.0.1"))
                .isInstanceOf(RuntimeException.class);
        verify(phoneProvider, times(10)).start(anyString());
    }

    @Test
    void phoneStartEndpointReturnsOkAndOriginalSessionForRepeatedPendingStart() {
        when(phoneProvider.start("+79991234567")).thenReturn(new PhoneVerificationStart("check-http-idempotent", "7800", "+7 800", Instant.now().plusSeconds(300)));

        PhoneVerificationStartResponse first = webTestClient.post().uri("/api/v1/auth/phone/start").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"phone\":\"+79991234567\"}").exchange().expectStatus().isOk()
                .expectBody(PhoneVerificationStartResponse.class).returnResult().getResponseBody();
        PhoneVerificationStartResponse repeated = webTestClient.post().uri("/api/v1/auth/phone/start").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"phone\":\"+79991234567\"}").exchange().expectStatus().isOk()
                .expectBody(PhoneVerificationStartResponse.class).returnResult().getResponseBody();

        assertThat(repeated.verificationId()).isEqualTo(first.verificationId());
        assertThat(repeated.callPhone()).isEqualTo(first.callPhone());
        assertThat(repeated.callPhonePretty()).isEqualTo(first.callPhonePretty());
        verify(phoneProvider, times(1)).start("+79991234567");
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
    private static List<PhoneVerificationStartResponse> parallelStarts(java.util.concurrent.Callable<PhoneVerificationStartResponse> action) {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            return List.of(CompletableFuture.supplyAsync(() -> call(action), executor),
                    CompletableFuture.supplyAsync(() -> call(action), executor)).stream().map(CompletableFuture::join).toList();
        } finally { executor.shutdownNow(); }
    }
    private static <T> T call(java.util.concurrent.Callable<T> action) { try { return action.call(); } catch (Exception e) { throw new RuntimeException(e); } }
}
