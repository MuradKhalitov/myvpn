package ru.murad.myvpn.client;

import ru.murad.myvpn.exception.ThreeXUiException;

import java.util.regex.Pattern;

public record PublicVpnHost(String value) {

    private static final Pattern HOST_LABEL = Pattern.compile(
            "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?");

    public PublicVpnHost {
        value = validateAndNormalize(value);
    }

    private static String validateAndNormalize(String candidate) {
        if (candidate == null || candidate.isBlank()
                || !candidate.equals(candidate.trim())
                || candidate.chars().anyMatch(
                value -> Character.isWhitespace(value)
                        || Character.isISOControl(value))
                || candidate.indexOf('%') >= 0
                || containsForbiddenDelimiter(candidate)) {
            throw invalid();
        }
        boolean bracketed = candidate.startsWith("[")
                || candidate.endsWith("]");
        if (bracketed) {
            if (!(candidate.startsWith("[") && candidate.endsWith("]"))) {
                throw invalid();
            }
            candidate = candidate.substring(1, candidate.length() - 1);
            validateIpv6(candidate);
            return candidate;
        }
        if (candidate.contains(":")) {
            validateIpv6(candidate);
            return candidate;
        }
        if (candidate.chars().allMatch(
                value -> Character.isDigit(value) || value == '.')) {
            validateIpv4(candidate);
            return candidate;
        }
        validateHostname(candidate);
        return candidate;
    }

    private static boolean containsForbiddenDelimiter(String value) {
        return value.contains("://")
                || value.indexOf('/') >= 0
                || value.indexOf('?') >= 0
                || value.indexOf('#') >= 0
                || value.indexOf('@') >= 0;
    }

    private static void validateIpv6(String value) {
        if (value.isEmpty()
                || value.chars().anyMatch(character ->
                character != ':' && Character.digit(character, 16) < 0)
                || value.indexOf("::") != value.lastIndexOf("::")) {
            throw invalid();
        }
        int compression = value.indexOf("::");
        if (compression < 0) {
            if (countAndValidateIpv6Groups(value) != 8) {
                throw invalid();
            }
        } else {
            String left = value.substring(0, compression);
            String right = value.substring(compression + 2);
            int explicitGroups = countAndValidateIpv6Groups(left)
                    + countAndValidateIpv6Groups(right);
            if (explicitGroups >= 8) {
                throw invalid();
            }
        }
        if (value.chars().noneMatch(character ->
                Character.digit(character, 16) > 0)) {
            throw invalid();
        }
    }

    private static int countAndValidateIpv6Groups(String value) {
        if (value.isEmpty()) {
            return 0;
        }
        String[] groups = value.split(":", -1);
        for (String group : groups) {
            if (group.isEmpty() || group.length() > 4
                    || group.chars().anyMatch(
                    character -> Character.digit(character, 16) < 0)) {
                throw invalid();
            }
        }
        return groups.length;
    }

    private static void validateIpv4(String value) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) {
            throw invalid();
        }
        boolean unspecified = true;
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3
                    || octet.length() > 1 && octet.charAt(0) == '0') {
                throw invalid();
            }
            int numeric = Integer.parseInt(octet);
            if (numeric > 255) {
                throw invalid();
            }
            unspecified &= numeric == 0;
        }
        if (unspecified) {
            throw invalid();
        }
    }

    private static void validateHostname(String value) {
        if (value.length() > 253) {
            throw invalid();
        }
        String[] labels = value.split("\\.", -1);
        for (String label : labels) {
            if (!HOST_LABEL.matcher(label).matches()) {
                throw invalid();
            }
        }
    }

    private static ThreeXUiException invalid() {
        return new ThreeXUiException("Invalid public VPN host");
    }

    @Override
    public String toString() {
        return "PublicVpnHost[redacted]";
    }
}
