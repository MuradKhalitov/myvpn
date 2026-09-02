package ru.murad.myvpn.service;

import ru.murad.myvpn.model.VpnEntitlement;
import java.time.Instant;
import java.util.UUID;

public record VpnTrafficPolicyCandidate(UUID accessId, UUID accountId, String externalAccessId,
        String providerClientKey, VpnEntitlement entitlement, long generation,
        Instant provisioningExpiresAt, boolean requiresProvisioning) {
    public VpnTrafficPolicyCandidate(UUID accessId, String externalAccessId, String providerClientKey,
            VpnEntitlement entitlement, long generation) {
        this(accessId, null, externalAccessId, providerClientKey, entitlement, generation, null, false);
    }

    @Override public String toString() {
        return "VpnTrafficPolicyCandidate[accessId=" + accessId + ", entitlement="
                + entitlement + ", generation=" + generation + ", requiresProvisioning="
                + requiresProvisioning + "]";
    }
}
