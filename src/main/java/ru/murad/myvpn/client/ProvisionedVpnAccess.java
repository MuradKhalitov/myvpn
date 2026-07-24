package ru.murad.myvpn.client;

public record ProvisionedVpnAccess(
        String providerName,
        String externalAccessId,
        String configurationData
) {

    @Override
    public String toString() {
        return "ProvisionedVpnAccess[providerName=" + providerName
                + ", externalAccessId=redacted, configurationData=redacted]";
    }
}
