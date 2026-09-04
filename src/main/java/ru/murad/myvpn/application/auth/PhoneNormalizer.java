package ru.murad.myvpn.application.auth;

import org.springframework.stereotype.Component;

@Component
public class PhoneNormalizer {
    public String normalize(String input) {
        if (input == null) throw new InvalidAuthenticationException();
        String digits = input.replaceAll("[\\s()\\-]", "");
        if (digits.matches("8\\d{10}")) digits = "7" + digits.substring(1);
        if (digits.matches("7\\d{10}")) return "+" + digits;
        if (digits.matches("\\+7\\d{10}")) return digits;
        throw new InvalidAuthenticationException();
    }
}
