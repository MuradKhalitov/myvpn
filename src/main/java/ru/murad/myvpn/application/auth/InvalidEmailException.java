package ru.murad.myvpn.application.auth;

public class InvalidEmailException extends RuntimeException {

    public InvalidEmailException() {
        super("Invalid email address");
    }
}
