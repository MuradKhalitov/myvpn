package ru.murad.myvpn.exception;

public class ThreeXUiException extends RuntimeException {

    public ThreeXUiException(String message) {
        super(message);
    }

    public ThreeXUiException(String message, Throwable cause) {
        super(message, cause);
    }
}
