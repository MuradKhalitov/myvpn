package ru.murad.myvpn.exception;

public class VpnProviderPermanentException extends RuntimeException {
    private final VpnProviderFailureCode failureCode;

    public VpnProviderPermanentException(String ignoredMessage) {
        this(VpnProviderFailureCode.PERMANENT);
    }

    public VpnProviderPermanentException(VpnProviderFailureCode failureCode) {
        super("VPN provider permanent failure");
        this.failureCode = java.util.Objects.requireNonNull(failureCode, "failureCode");
    }

    public VpnProviderFailureCode safeFailureCode() {
        return failureCode;
    }
}
