package ru.murad.myvpn.exception;

public class ThreeXUiUncertainException extends ThreeXUiException {

    public ThreeXUiUncertainException() {
        this(VpnProviderFailureCode.MUTATION_RECONCILIATION_FAILED);
    }

    public ThreeXUiUncertainException(VpnProviderFailureCode failureCode) {
        super(failureCode, true, null,
                "3x-ui operation result requires reconciliation");
    }
}
