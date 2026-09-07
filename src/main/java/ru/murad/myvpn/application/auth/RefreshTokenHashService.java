package ru.murad.myvpn.application.auth;

import java.util.UUID;

public final class RefreshTokenHashService {

    private final SecretHmac hmac;

    public RefreshTokenHashService(String pepper) {
        this.hmac = new SecretHmac(pepper);
    }

    public String hash(String token) {
        return hmac.hash(token);
    }

    /** Derives an opaque rotated credential that can be reproduced after a lost response. */
    public String deriveRotatedToken(UUID sessionId, long rotationCounter) {
        return hmac.hash("refresh-rotation:" + sessionId + ":" + rotationCounter);
    }
}
