package ru.murad.myvpn.exception;

public class ThreeXUiLastClientException extends ThreeXUiException {

    public ThreeXUiLastClientException() {
        super("3x-ui client cannot be revoked while it is the last inbound client");
    }
}
