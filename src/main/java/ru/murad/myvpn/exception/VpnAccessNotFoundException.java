package ru.murad.myvpn.exception;

import java.util.UUID;

public class VpnAccessNotFoundException extends RuntimeException {

    public VpnAccessNotFoundException(UUID subscriptionId) {
        super("VPN access not found for subscription: " + subscriptionId);
    }
}
