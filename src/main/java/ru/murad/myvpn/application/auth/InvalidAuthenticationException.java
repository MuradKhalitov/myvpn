package ru.murad.myvpn.application.auth;

public class InvalidAuthenticationException extends RuntimeException {

    public InvalidAuthenticationException() {
        super("Authentication data is invalid or expired");
    }
}
