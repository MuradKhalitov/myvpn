package ru.murad.myvpn.exception;

public class ThreeXUiNotFoundException extends ThreeXUiException {

    public ThreeXUiNotFoundException(String operation) {
        super("3x-ui resource was not found during " + operation);
    }
}
