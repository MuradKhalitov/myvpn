package ru.murad.myvpn.exception;

public class VpnDeliveryMessageTooLongException extends RuntimeException {
    public VpnDeliveryMessageTooLongException() { super("VPN delivery message exceeds Telegram limit"); }
}
