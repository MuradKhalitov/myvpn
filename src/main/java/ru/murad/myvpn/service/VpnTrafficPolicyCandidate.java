package ru.murad.myvpn.service;

import ru.murad.myvpn.model.VpnEntitlement;
import java.util.UUID;

public record VpnTrafficPolicyCandidate(UUID accessId, String externalAccessId,
        VpnEntitlement entitlement, long generation) {
    @Override public String toString() {
        return "VpnTrafficPolicyCandidate[accessId=" + accessId + ", entitlement="
                + entitlement + ", generation=" + generation + "]";
    }
}
