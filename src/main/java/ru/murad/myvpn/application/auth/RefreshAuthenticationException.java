package ru.murad.myvpn.application.auth;

public class RefreshAuthenticationException extends RuntimeException {

    public enum Reason {
        REFRESH_TOKEN_INVALID,
        SESSION_REVOKED
    }

    private final Reason reason;

    public RefreshAuthenticationException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
