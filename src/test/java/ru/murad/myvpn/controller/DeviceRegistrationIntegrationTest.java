package ru.murad.myvpn.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import ru.murad.myvpn.model.AccountIdentityType;
import ru.murad.myvpn.model.DeviceCredential;
import ru.murad.myvpn.repository.SubscriptionRepository;
import ru.murad.myvpn.service.AccountVpnAccessService;
import ru.murad.myvpn.support.AuthIntegrationTestSupport;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

class DeviceRegistrationIntegrationTest extends AuthIntegrationTestSupport {

    private static final String INSTALL_ID = "8b60d9a2-59ed-42a8-a0cb-6b95797a5732";
    private static final String SECRET = "A".repeat(43);

    @Autowired private ObjectMapper objectMapper;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @MockBean private AccountVpnAccessService vpnAccessService;

    @Test
    void createsAccountOnlyDeviceIdentityCredentialSessionAndSchedulesFreeVpn() throws Exception {
        JsonNode response = register(INSTALL_ID, SECRET, 200);

        UUID accountId = UUID.fromString(response.path("accountId").asText());
        assertThat(response.path("accessToken").asText()).isNotBlank();
        assertThat(response.path("refreshToken").asText()).isNotBlank();
        assertThat(response.path("vpnStatus").asText()).isEqualTo("PROVISIONING");
        assertThat(accountRepository.findById(accountId)).isPresent();
        assertThat(identityRepository.findByTypeAndNormalizedSubject(AccountIdentityType.DEVICE, INSTALL_ID))
                .hasValueSatisfying(identity -> assertThat(identity.getAccount().getId()).isEqualTo(accountId));
        DeviceCredential credential = deviceCredentialRepository.findAll().get(0);
        assertThat(credential.getSecretHash()).isNotEqualTo(SECRET).hasSize(64);
        assertThat(telegramUserRepository.count()).isZero();
        assertThat(identityRepository.findAll()).allMatch(identity -> identity.getType() == AccountIdentityType.DEVICE);
        assertThat(subscriptionRepository.count()).isZero();
        assertThat(sessionRepository.findAll()).hasSize(1);
        Jwt jwt = jwtDecoder.decode(response.path("accessToken").asText());
        assertThat(jwt.getSubject()).isEqualTo(accountId.toString());
        verify(vpnAccessService).ensureFreeVpnAccess(accountId);
    }

    @Test
    void repeatRegistrationUsesSameAccountAndCreatesNewSession() throws Exception {
        UUID firstAccountId = UUID.fromString(register(INSTALL_ID, SECRET, 200).path("accountId").asText());
        UUID secondAccountId = UUID.fromString(register(INSTALL_ID, SECRET, 200).path("accountId").asText());

        assertThat(secondAccountId).isEqualTo(firstAccountId);
        assertThat(accountRepository.count()).isEqualTo(1);
        assertThat(identityRepository.count()).isEqualTo(1);
        assertThat(deviceCredentialRepository.count()).isEqualTo(1);
        assertThat(sessionRepository.count()).isEqualTo(2);
    }

    @Test
    void wrongSecretUsesGenericUnauthorizedErrorAndRevokedCredentialCannotAuthenticate() throws Exception {
        register(INSTALL_ID, SECRET, 200);
        register(INSTALL_ID, "B".repeat(43), 401);
        DeviceCredential credential = deviceCredentialRepository.findAll().get(0);
        credential.revoke(java.time.Instant.now());
        deviceCredentialRepository.saveAndFlush(credential);

        JsonNode revoked = register(INSTALL_ID, SECRET, 401);
        assertThat(revoked.path("code").asText()).isEqualTo("INVALID_AUTHENTICATION");
    }

    private JsonNode register(String installId, String secret, int status) throws Exception {
        byte[] response = webTestClient.post().uri("/api/v1/device/register")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"installId\":\"" + installId + "\",\"deviceSecret\":\"" + secret + "\"}")
                .exchange()
                .expectStatus().isEqualTo(status)
                .expectBody().returnResult().getResponseBody();
        return objectMapper.readTree(new String(response, StandardCharsets.UTF_8));
    }
}
