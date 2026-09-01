package ru.murad.myvpn.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import ru.murad.myvpn.application.vpn.CurrentVpnAccessQuery;
import ru.murad.myvpn.application.vpn.VpnAccessApiStatus;
import ru.murad.myvpn.application.vpn.VpnAccessResponse;
import ru.murad.myvpn.model.VpnEntitlement;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class VpnAccessControllerTest {

    @Test
    void resolvesAccountOnlyFromJwtAndSetsNoStoreHeaders() {
        CurrentVpnAccessQuery query = mock(CurrentVpnAccessQuery.class);
        UUID accountId = UUID.randomUUID();
        when(query.getCurrentAccess(accountId)).thenReturn(
                new VpnAccessResponse(VpnAccessApiStatus.READY, VpnEntitlement.FREE,
                        "vless://sensitive", null, null));
        VpnAccessController controller = new VpnAccessController(query);
        Jwt jwt = Jwt.withTokenValue("token").subject(accountId.toString()).header("alg", "none").build();

        var response = controller.access(jwt).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getHeaders().getFirst("Pragma")).isEqualTo("no-cache");
        assertThat(response.getBody().configuration()).isEqualTo("vless://sensitive");
        verify(query).getCurrentAccess(accountId);
        verifyNoMoreInteractions(query);
    }
}
