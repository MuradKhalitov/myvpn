package ru.murad.myvpn.exception;

public class ThreeXUiAuthenticationException extends ThreeXUiException {

    public ThreeXUiAuthenticationException() {
        super(VpnProviderFailureCode.AUTHENTICATION_FAILED, false, null,
                "3x-ui authentication failed");
    }
}
