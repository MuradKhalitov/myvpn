package ru.murad.myvpn.client;

public interface VpnProvider {

    String providerName();

    java.time.Instant resolveProvisionTarget(
            VpnProvisionRequest request,
            int durationDays,
            java.time.Instant now
    );

    ProvisionedVpnAccess provision(VpnProvisionRequest request);

    ProvisionedVpnAccess extend(VpnExtensionRequest request);

    void applyTrafficPolicy(String externalAccessId, VpnTrafficPolicy policy);

    default void applyTrafficPolicy(String externalAccessId, String providerClientKey,
            VpnTrafficPolicy policy) {
        applyTrafficPolicy(externalAccessId, policy);
    }

    default void setAccessEnabled(String externalAccessId, boolean enabled) { }

    void revoke(String externalAccessId);
}
