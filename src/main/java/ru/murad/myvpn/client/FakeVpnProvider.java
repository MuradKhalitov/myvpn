package ru.murad.myvpn.client;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.UUID;

@Component
@ConditionalOnProperty(
        name = "vpn.provider.type",
        havingValue = "fake",
        matchIfMissing = true
)
public class FakeVpnProvider implements VpnProvider {

    private static final String PROVIDER_NAME = "FAKE";

    @Override
    public ProvisionedVpnAccess provision(VpnProvisionRequest request) {
        String externalAccessId = UUID.randomUUID().toString();
        String configuration = "fake-vpn://" + externalAccessId;
        return new ProvisionedVpnAccess(PROVIDER_NAME, externalAccessId, configuration);
    }

    @Override
    public void extend(VpnExtensionRequest request) {
        // The fake provider has no external state to update.
    }

    @Override
    public void revoke(String externalAccessId) {
        // The fake provider has no external state to revoke.
    }
}
