package ru.murad.myvpn.exception;

public class VpnTariffNotFoundException extends RuntimeException {

    public VpnTariffNotFoundException(String tariffCode) {
        super("Active VPN tariff not found: " + tariffCode);
    }
}
