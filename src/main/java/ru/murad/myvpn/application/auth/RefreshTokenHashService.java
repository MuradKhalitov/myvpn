package ru.murad.myvpn.application.auth;

public final class RefreshTokenHashService {

    private final SecretHmac hmac;

    public RefreshTokenHashService(String pepper) {
        this.hmac = new SecretHmac(pepper);
    }

    public String hash(String token) {
        return hmac.hash(token);
    }
}
