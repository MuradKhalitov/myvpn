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

    void revoke(String externalAccessId);
}
