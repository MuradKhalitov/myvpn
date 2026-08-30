package ru.murad.myvpn.application.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class SecretHmac {

    private static final String ALGORITHM = "HmacSHA256";
    private final SecretKeySpec key;

    public SecretHmac(String pepper) {
        this.key = new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    public String hash(String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC is unavailable", exception);
        }
    }

    public boolean matches(String value, String expectedHash) {
        return MessageDigest.isEqual(
                hash(value).getBytes(StandardCharsets.US_ASCII),
                expectedHash.getBytes(StandardCharsets.US_ASCII));
    }
}
