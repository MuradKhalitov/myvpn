package ru.murad.myvpn.client;

/** Provider-neutral byte limit.  The provider maps it to its documented wire field. */
public record VpnTrafficPolicy(long totalGbValue, boolean unlimited) {
    public VpnTrafficPolicy {
        if (totalGbValue < 0) throw new IllegalArgumentException("Traffic limit must not be negative");
    }

    public static VpnTrafficPolicy limited(long bytes) { return new VpnTrafficPolicy(bytes, false); }
    public static VpnTrafficPolicy unlimited(long providerUnlimitedValue) {
        return new VpnTrafficPolicy(providerUnlimitedValue, true);
    }
}
