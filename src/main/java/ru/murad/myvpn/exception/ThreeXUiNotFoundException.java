package ru.murad.myvpn.exception;

public class ThreeXUiNotFoundException extends ThreeXUiException {

    public ThreeXUiNotFoundException(String operation) {
        super(VpnProviderFailureCode.CLIENT_NOT_FOUND, false, null,
                "3x-ui resource was not found");
    }
}
