package ru.murad.myvpn.client;

public record VlessConfigurationData(
        String clientId,
        String publicHost,
        int publicPort,
        String network,
        String security,
        String encryption,
        String flow,
        String serverName,
        String fingerprint,
        String publicKey,
        String shortId,
        String spiderX,
        String displayName
) {

    @Override
    public String toString() {
        return "VlessConfigurationData[redacted]";
    }
}
