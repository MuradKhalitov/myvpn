package ru.murad.myvpn.exception;

public class ThreeXUiUncertainException extends ThreeXUiException {

    public ThreeXUiUncertainException() {
        super("3x-ui operation result requires reconciliation");
    }
}
