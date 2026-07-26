package ru.murad.myvpn.exception;

/** Internal safe signal that a claimed delivery no longer has an owner-consistent graph. */
public class VpnDeliveryRelationshipMismatchException extends RuntimeException {
    public VpnDeliveryRelationshipMismatchException() {
        super("VPN delivery relationship graph is invalid");
    }
}
