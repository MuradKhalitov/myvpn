package ru.murad.myvpn.client;

public interface VpnProvider {

    ProvisionedVpnAccess provision(VpnProvisionRequest request);

    void extend(VpnExtensionRequest request);

    void revoke(String externalAccessId);
}
