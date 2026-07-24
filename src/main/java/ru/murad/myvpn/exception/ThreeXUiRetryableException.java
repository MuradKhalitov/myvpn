package ru.murad.myvpn.exception;

public class ThreeXUiRetryableException extends ThreeXUiException {

    public ThreeXUiRetryableException(String message) {
        super(message);
    }

    public ThreeXUiRetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
