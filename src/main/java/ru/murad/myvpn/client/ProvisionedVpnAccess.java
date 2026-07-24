package ru.murad.myvpn.client;

public record ProvisionedVpnAccess(
        String providerName,
        String externalAccessId,
        String configurationData
) {
}
