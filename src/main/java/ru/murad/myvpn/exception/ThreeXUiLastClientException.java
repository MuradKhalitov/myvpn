package ru.murad.myvpn.exception;

public class ThreeXUiLastClientException extends ThreeXUiException {

    public ThreeXUiLastClientException() {
        super(VpnProviderFailureCode.CLIENT_CONFLICT, false, null,
                "3x-ui client cannot be revoked");
    }
}
