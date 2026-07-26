package ru.murad.myvpn.client;

public interface VpnProvider {

    String providerName();

    ProvisionedVpnAccess provision(VpnProvisionRequest request);

    ProvisionedVpnAccess extend(VpnExtensionRequest request);

    void revoke(String externalAccessId);
}
