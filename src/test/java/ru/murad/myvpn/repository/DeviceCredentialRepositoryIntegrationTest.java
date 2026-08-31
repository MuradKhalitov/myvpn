package ru.murad.myvpn.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.murad.myvpn.model.AccountIdentityType;
import ru.murad.myvpn.support.AuthIntegrationTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceCredentialRepositoryIntegrationTest extends AuthIntegrationTestSupport {

    @Autowired private ru.murad.myvpn.application.auth.DeviceRegistrationService registrationService;

    @Test
    void credentialIsAddressableOnlyThroughItsDeviceIdentity() {
        String installId = "8b60d9a2-59ed-42a8-a0cb-6b95797a5732";
        registrationService.register(installId, "A".repeat(43));

        var identity = identityRepository.findByTypeAndNormalizedSubject(AccountIdentityType.DEVICE, installId)
                .orElseThrow();
        assertThat(deviceCredentialRepository.findByIdentityId(identity.getId()))
                .hasValueSatisfying(credential -> assertThat(credential.getSecretHash()).hasSize(64));
    }
}
