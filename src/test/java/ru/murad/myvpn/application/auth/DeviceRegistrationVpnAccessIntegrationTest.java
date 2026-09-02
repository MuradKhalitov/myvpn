package ru.murad.myvpn.application.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.murad.myvpn.dto.DeviceRegisterRequest;
import ru.murad.myvpn.model.AccountIdentityType;
import ru.murad.myvpn.model.VpnAccessStatus;
import ru.murad.myvpn.model.VpnEntitlement;
import ru.murad.myvpn.support.AuthIntegrationTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceRegistrationVpnAccessIntegrationTest extends AuthIntegrationTestSupport {
    private static final String INSTALL_ID = "5f9c0d6f-4e41-4dfe-a99b-b7ca9d14fb98";
    private static final String SECRET = "B".repeat(43);
    @Autowired private ObjectMapper objectMapper;

    @Test
    void deviceRegistrationCreatesCanonicalFreeAccessAndReturnsReadyConfiguration() {
        Registration first = register();
        var access = vpnAccessRepository.findByAccountId(first.accountId()).orElseThrow();

        assertThat(accountRepository.count()).isEqualTo(1);
        assertThat(identityRepository.findByTypeAndNormalizedSubject(AccountIdentityType.DEVICE, INSTALL_ID)).isPresent();
        assertThat(deviceCredentialRepository.count()).isEqualTo(1);
        assertThat(sessionRepository.count()).isEqualTo(1);
        assertThat(access.getSubscription()).isNull();
        assertThat(access.getDesiredEntitlement()).isEqualTo(VpnEntitlement.FREE);
        assertThat(access.getStatus()).isEqualTo(VpnAccessStatus.ACTIVE);
        assertThat(access.getConfigurationData()).isNotBlank();

        webTestClient.get().uri("/api/v1/vpn/access")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + first.accessToken())
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("READY")
                .jsonPath("$.configuration").isNotEmpty();

        Registration second = register();
        assertThat(second.accountId()).isEqualTo(first.accountId());
        assertThat(vpnAccessRepository.count()).isEqualTo(1);
    }

    private Registration register() {
        byte[] body = webTestClient.post().uri("/api/v1/device/register")
                .bodyValue(new DeviceRegisterRequest(INSTALL_ID, SECRET))
                .exchange().expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();
        try {
            var json = objectMapper.readTree(body);
            return new Registration(java.util.UUID.fromString(json.get("accountId").asText()), json.get("accessToken").asText());
        } catch (Exception exception) {
            throw new AssertionError("Registration response could not be decoded", exception);
        }
    }

    private record Registration(java.util.UUID accountId, String accessToken) { }
}
