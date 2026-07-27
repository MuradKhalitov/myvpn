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
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public java.time.Instant resolveProvisionTarget(
            VpnProvisionRequest request,
            int durationDays,
            java.time.Instant now
    ) {
        if (durationDays <= 0) {
            throw new IllegalArgumentException("Provision duration must be positive");
        }
        return now.plus(java.time.Duration.ofDays(durationDays));
    }

    @Override
    public ProvisionedVpnAccess provision(VpnProvisionRequest request) {
        String externalAccessId = request.stableExternalAccessId() == null
                ? request.subscriptionId().toString() : request.stableExternalAccessId();
        String configuration = "fake-vpn://" + externalAccessId;
        return new ProvisionedVpnAccess(PROVIDER_NAME, externalAccessId, configuration, request.expiresAt());
    }

    @Override
    public ProvisionedVpnAccess extend(VpnExtensionRequest request) {
        // The fake provider has no external state to update.
        return new ProvisionedVpnAccess(PROVIDER_NAME, request.externalAccessId(), null, request.expiresAt());
    }

    @Override
    public void revoke(String externalAccessId) {
        // The fake provider has no external state to revoke.
    }
}
