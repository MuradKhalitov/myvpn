package ru.murad.myvpn.application.auth;

public final class OtpHashService {

    private final SecretHmac hmac;

    public OtpHashService(String pepper) {
        this.hmac = new SecretHmac(pepper);
    }

    public String hash(String code) {
        return hmac.hash(code);
    }

    public boolean matches(String code, String expectedHash) {
        return hmac.matches(code, expectedHash);
    }
}
