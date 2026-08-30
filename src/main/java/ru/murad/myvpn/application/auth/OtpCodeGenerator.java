package ru.murad.myvpn.application.auth;

import java.security.SecureRandom;

public class OtpCodeGenerator {

    private final SecureRandom secureRandom;

    public OtpCodeGenerator(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    public String generate() {
        return String.format("%06d", secureRandom.nextInt(1_000_000));
    }
}
