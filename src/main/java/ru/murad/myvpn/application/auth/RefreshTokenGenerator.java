package ru.murad.myvpn.application.auth;

import java.security.SecureRandom;
import java.util.Base64;

public class RefreshTokenGenerator {

    private static final int TOKEN_BYTES = 32;
    private final SecureRandom secureRandom;

    public RefreshTokenGenerator(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    public String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
