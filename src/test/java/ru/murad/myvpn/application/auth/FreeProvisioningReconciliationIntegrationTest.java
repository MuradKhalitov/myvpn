package ru.murad.myvpn.application.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import ru.murad.myvpn.client.ProvisionedVpnAccess;
import ru.murad.myvpn.client.VpnProvider;
import ru.murad.myvpn.client.VpnProvisionRequest;
import ru.murad.myvpn.dto.DeviceRegisterRequest;
import ru.murad.myvpn.model.VpnAccessStatus;
import ru.murad.myvpn.service.VpnTrafficPolicyService;
import ru.murad.myvpn.support.AuthIntegrationTestSupport;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FreeProvisioningReconciliationIntegrationTest extends AuthIntegrationTestSupport {
    private static final String INSTALL_ID = "c044e082-7aeb-4ec1-90c6-29ec8f46f2cc";
    private static final String SECRET = "C".repeat(43);

    @Autowired private ObjectMapper objectMapper;
    @Autowired private VpnTrafficPolicyService policyService;
    @MockBean private VpnProvider vpnProvider;

    @Test
    void failedInitialFreeProvisioningIsRetriedAndFinalizedWithoutDuplicatingAccess() {
        when(vpnProvider.providerName()).thenReturn("FAKE");
        when(vpnProvider.provision(any()))
                .thenThrow(new RuntimeException("provider temporarily unavailable"))
                .thenAnswer(invocation -> {
                    VpnProvisionRequest request = invocation.getArgument(0);
                    return new ProvisionedVpnAccess("FAKE", request.stableExternalAccessId(), "fake-vpn://redacted");
                });

        Registration registration = register();
        var reserved = vpnAccessRepository.findByAccountId(registration.accountId()).orElseThrow();
        assertThat(reserved.getStatus()).isEqualTo(VpnAccessStatus.PROVISIONING);
        assertThat(reserved.getConfigurationData()).isNull();
        assertThat(sessionRepository.count()).isEqualTo(1);

        assertThat(policyService.reconcileDuePolicies()).isEqualTo(1);

        var completed = vpnAccessRepository.findByAccountId(registration.accountId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(VpnAccessStatus.ACTIVE);
        assertThat(completed.getConfigurationData()).isNotBlank();
        assertThat(vpnAccessRepository.count()).isEqualTo(1);
        webTestClient.get().uri("/api/v1/vpn/access")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + registration.accessToken())
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("READY")
                .jsonPath("$.configuration").isNotEmpty();

        ArgumentCaptor<VpnProvisionRequest> requests = ArgumentCaptor.forClass(VpnProvisionRequest.class);
        verify(vpnProvider, times(2)).provision(requests.capture());
        assertThat(requests.getAllValues()).allSatisfy(request -> {
            assertThat(request.stableExternalAccessId()).isEqualTo(registration.accountId().toString());
            assertThat(request.providerClientKey()).isEqualTo("acc_" + registration.accountId());
        });

        Registration repeated = register();
        assertThat(repeated.accountId()).isEqualTo(registration.accountId());
        assertThat(vpnAccessRepository.count()).isEqualTo(1);
        verify(vpnProvider, times(2)).provision(any());
    }

    private Registration register() {
        byte[] body = webTestClient.post().uri("/api/v1/device/register")
                .bodyValue(new DeviceRegisterRequest(INSTALL_ID, SECRET))
                .exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();
        try {
            JsonNode json = objectMapper.readTree(body);
            return new Registration(UUID.fromString(json.get("accountId").asText()), json.get("accessToken").asText());
        } catch (Exception exception) {
            throw new AssertionError("Registration response could not be decoded", exception);
        }
    }

    private record Registration(UUID accountId, String accessToken) { }
}
