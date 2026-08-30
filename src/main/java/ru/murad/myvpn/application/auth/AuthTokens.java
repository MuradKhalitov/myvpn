package ru.murad.myvpn.application.auth;

import java.time.Duration;

public final class AuthTokens {

    private final String accessToken;
    private final String refreshToken;
    private final String tokenType;
    private final long expiresIn;

    public AuthTokens(String accessToken, String refreshToken, Duration accessTtl) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.tokenType = "Bearer";
        this.expiresIn = accessTtl.toSeconds();
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public String getTokenType() {
        return tokenType;
    }

    public long getExpiresIn() {
        return expiresIn;
    }

    @Override
    public String toString() {
        return "AuthTokens[accessToken=<redacted>, refreshToken=<redacted>, tokenType=Bearer, expiresIn="
                + expiresIn + "]";
    }
}
