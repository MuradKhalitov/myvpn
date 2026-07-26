package ru.murad.myvpn.client;

public record ProvisionedVpnAccess(
        String providerName,
        String externalAccessId,
        String configurationData,
        java.time.Instant targetExpiresAt
) {

    public ProvisionedVpnAccess(String providerName, String externalAccessId, String configurationData) {
        this(providerName, externalAccessId, configurationData, null);
    }

    @Override
    public String toString() {
        return "ProvisionedVpnAccess[providerName=" + providerName
                + ", externalAccessId=redacted, configurationData=redacted]";
    }
}
