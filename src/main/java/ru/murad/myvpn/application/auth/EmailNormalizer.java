package ru.murad.myvpn.application.auth;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class EmailNormalizer {

    private static final int MAX_EMAIL_LENGTH = 320;
    private static final Pattern EMAIL = Pattern.compile(
            "^[a-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}@[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?$");

    public String normalize(String value) {
        if (value == null) {
            throw new InvalidEmailException();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() < 3 || normalized.length() > MAX_EMAIL_LENGTH
                || normalized.chars().anyMatch(Character::isISOControl)
                || normalized.chars().anyMatch(Character::isWhitespace)
                || !EMAIL.matcher(normalized).matches()
                || normalized.contains("..")) {
            throw new InvalidEmailException();
        }
        String domain = normalized.substring(normalized.lastIndexOf('@') + 1);
        if (!domain.contains(".") || domain.startsWith(".") || domain.endsWith(".")) {
            throw new InvalidEmailException();
        }
        for (String label : domain.split("\\.")) {
            if (label.isEmpty() || label.length() > 63
                    || label.startsWith("-") || label.endsWith("-")) {
                throw new InvalidEmailException();
            }
        }
        return normalized;
    }
}
