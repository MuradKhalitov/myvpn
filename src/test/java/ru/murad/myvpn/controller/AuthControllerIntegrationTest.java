package ru.murad.myvpn.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import ru.murad.myvpn.application.auth.AuthTokens;
import ru.murad.myvpn.application.auth.RefreshTokenService;
import ru.murad.myvpn.support.AuthIntegrationTestSupport;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

class AuthControllerIntegrationTest extends AuthIntegrationTestSupport {

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RefreshTokenService refreshTokenService;

    @Test
    void fullOtpRefreshRotationAndLogoutFlow() throws Exception {
        webTestClient.post().uri("/api/v1/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\" User@Example.com \"}")
                .exchange()
                .expectStatus().isAccepted()
                .expectBody().isEmpty();
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendOtp(any(), code.capture(), any());

        JsonNode verified = postJson("/api/v1/auth/otp/verify",
                "{\"email\":\"user@example.com\",\"code\":\"" + code.getValue() + "\"}",
                200);
        String firstRefresh = verified.path("refreshToken").asText();
        assertThat(verified.path("accessToken").asText()).isNotBlank();

        JsonNode rotated = postJson("/api/v1/auth/refresh",
                "{\"refreshToken\":\"" + firstRefresh + "\"}", 200);
        String nextRefresh = rotated.path("refreshToken").asText();
        assertThat(nextRefresh).isNotEqualTo(firstRefresh);

        JsonNode recovered = postJson("/api/v1/auth/refresh",
                "{\"refreshToken\":\"" + firstRefresh + "\"}", 200);
        assertThat(recovered.path("refreshToken").asText()).isEqualTo(nextRefresh);

        webTestClient.post().uri("/api/v1/auth/logout")
                .header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + rotated.path("accessToken").asText())
                .exchange()
                .expectStatus().isNoContent();
        assertThat(sessionRepository.findAll()).singleElement()
                .extracting(session -> session.getRevokedAt()).isNotNull();

        JsonNode revoked = postJson("/api/v1/auth/refresh",
                "{\"refreshToken\":\"" + nextRefresh + "\"}", 401);
        assertThat(revoked.path("code").asText()).isEqualTo("SESSION_REVOKED");
        JsonNode revokedPrevious = postJson("/api/v1/auth/refresh",
                "{\"refreshToken\":\"" + firstRefresh + "\"}", 401);
        assertThat(revokedPrevious.path("code").asText()).isEqualTo("SESSION_REVOKED");

        JsonNode invalid = postJson("/api/v1/auth/refresh",
                "{\"refreshToken\":\"unknown-refresh-token\"}", 401);
        assertThat(invalid.path("code").asText()).isEqualTo("REFRESH_TOKEN_INVALID");
    }

    @Test
    void requestIsGenericForInvalidEmailAndVerifyErrorsAreUniform() throws Exception {
        postJson("/api/v1/auth/otp/request", "{\"email\":\"invalid\"}", 202);
        JsonNode response = postJson("/api/v1/auth/otp/verify",
                "{\"email\":\"unknown@example.com\",\"code\":\"000000\"}", 401);

        assertThat(response.path("code").asText()).isEqualTo("INVALID_AUTHENTICATION");
    }

    @Test
    void concurrentRefreshReturnsOneIdempotentRotationToBothCallers() throws Exception {
        webTestClient.post().uri("/api/v1/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"user@example.com\"}").exchange().expectStatus().isAccepted();
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailSender).sendOtp(any(), code.capture(), any());
        String refresh = postJson("/api/v1/auth/otp/verify",
                "{\"email\":\"user@example.com\",\"code\":\"" + code.getValue() + "\"}", 200)
                .path("refreshToken").asText();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<String> rotatedTokens = List.of(
                    CompletableFuture.supplyAsync(() -> refreshToken(refresh), executor),
                    CompletableFuture.supplyAsync(() -> refreshToken(refresh), executor))
                    .stream().map(CompletableFuture::join).toList();
            assertThat(rotatedTokens).hasSize(2).allMatch(rotatedTokens.get(0)::equals);
        } finally {
            executor.shutdownNow();
        }
        assertThat(sessionRepository.findAll()).singleElement()
                .satisfies(session -> assertThat(session.getRotationCounter()).isEqualTo(1));
    }

    private String refreshToken(String refresh) {
        AuthTokens tokens = refreshTokenService.refresh(refresh);
        return tokens.getRefreshToken();
    }

    private JsonNode postJson(String uri, String body, int status) throws Exception {
        byte[] response = webTestClient.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isEqualTo(status)
                .expectBody().returnResult().getResponseBody();
        if (response == null || response.length == 0) {
            return objectMapper.createObjectNode();
        }
        return objectMapper.readTree(new String(response, StandardCharsets.UTF_8));
    }
}
