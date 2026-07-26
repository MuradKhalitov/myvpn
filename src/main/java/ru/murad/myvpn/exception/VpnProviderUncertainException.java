package ru.murad.myvpn.exception;

public class VpnProviderUncertainException extends RuntimeException {
    public VpnProviderUncertainException() { super("VPN provider result is uncertain"); }
}
