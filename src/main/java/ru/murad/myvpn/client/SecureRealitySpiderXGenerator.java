package ru.murad.myvpn.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
@ConditionalOnProperty(name = "vpn.provider.type", havingValue = "3x-ui")
public class SecureRealitySpiderXGenerator
        implements RealitySpiderXGenerator {

    private static final char[] ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                    .toCharArray();
    private static final int LENGTH = 15;

    private final SecureRandom secureRandom;

    public SecureRealitySpiderXGenerator() {
        this(new SecureRandom());
    }

    SecureRealitySpiderXGenerator(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    @Override
    public String generate() {
        StringBuilder value = new StringBuilder(LENGTH + 1).append('/');
        for (int index = 0; index < LENGTH; index++) {
            value.append(ALPHABET[secureRandom.nextInt(ALPHABET.length)]);
        }
        return value.toString();
    }
}
