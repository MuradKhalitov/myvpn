package ru.murad.myvpn.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import ru.murad.myvpn.application.auth.JwtTokenService;
import ru.murad.myvpn.support.AuthIntegrationTestSupport;

import java.util.UUID;

class SecurityConfigurationIntegrationTest extends AuthIntegrationTestSupport {

    @Autowired
    private JwtTokenService jwtTokenService;

    @Test
    void protectedApiRejectsMissingAndInvalidJwt() {
        webTestClient.get().uri("/api/v1/protected-resource")
                .exchange().expectStatus().isUnauthorized()
                .expectHeader().value(HttpHeaders.WWW_AUTHENTICATE, value ->
                        org.assertj.core.api.Assertions.assertThat(value).startsWith("Bearer").doesNotContain("Basic"));
        webTestClient.get().uri("/api/v1/vpn/access")
                .exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri("/api/v1/vpn/access")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid")
                .exchange().expectStatus().isUnauthorized();
        webTestClient.get().uri("/api/v1/protected-resource")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid")
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void validJwtAuthenticatesAccountSubjectAndSessionClaim() {
        String token = jwtTokenService.issue(UUID.randomUUID(), UUID.randomUUID());

        webTestClient.get().uri("/api/v1/protected-resource")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void existingNonV1EndpointRemainsUnauthenticated() {
        webTestClient.get().uri("/actuator/health")
                .exchange().expectStatus().isOk();
    }

    @Test
    void androidVersionEndpointIsPublicWhileProtectedEndpointsStillRequireBearer() {
        webTestClient.get().uri("/api/v1/app/version")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.latestVersionCode").isEqualTo(1)
                .jsonPath("$.latestVersionName").isEqualTo("1.0.0")
                .jsonPath("$.minimumSupportedVersionCode").isEqualTo(1)
                .jsonPath("$.apkUrl").isEqualTo("https://api.myvpn05.ru/downloads/myvpn-1.0.0.apk")
                .jsonPath("$.changelog").isEqualTo("Первая публичная версия MyVPN");
        webTestClient.get().uri("/api/v1/vpn/access")
                .exchange().expectStatus().isUnauthorized();
    }
}
