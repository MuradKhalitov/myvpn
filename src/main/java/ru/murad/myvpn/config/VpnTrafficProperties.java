package ru.murad.myvpn.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "vpn.free")
public record VpnTrafficProperties(long trafficLimitBytes, int quotaPeriodDays,
        long premiumUnlimitedTotalGb) {
    public VpnTrafficProperties {
        if (trafficLimitBytes <= 0 || quotaPeriodDays <= 0 || premiumUnlimitedTotalGb < 0) {
            throw new IllegalArgumentException("VPN traffic policy properties are invalid");
        }
    }
}
