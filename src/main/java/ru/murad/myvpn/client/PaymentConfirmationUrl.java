package ru.murad.myvpn.client;

import ru.murad.myvpn.exception.PaymentProviderPermanentException;

import java.net.URI;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class PaymentConfirmationUrl {

    private static final Pattern FAKE_PATH =
            Pattern.compile("/fake-pay/[A-Za-z0-9_-]{16,128}");
    private final URI value;

    private PaymentConfirmationUrl(URI value) {
        this.value = value;
    }

    public static PaymentConfirmationUrl fake(URI value) {
        PaymentConfirmationUrl result = validated(value, Set.of("example.invalid"));
        if (value.getPort() != -1
                || value.getQuery() != null
                || !FAKE_PATH.matcher(value.getPath()).matches()) {
            throw invalid();
        }
        return result;
    }

    private static PaymentConfirmationUrl validated(URI value, Set<String> allowedHosts) {
        Objects.requireNonNull(value, "value");
        if (!"https".equals(value.getScheme())
                || value.getHost() == null
                || !allowedHosts.contains(value.getHost())
                || value.getUserInfo() != null
                || value.getFragment() != null) {
            throw invalid();
        }
        return new PaymentConfirmationUrl(value);
    }

    private static PaymentProviderPermanentException invalid() {
        return new PaymentProviderPermanentException(
                "Payment confirmation URL is not allowed");
    }

    public URI value() {
        return value;
    }

    @Override
    public String toString() {
        return "PaymentConfirmationUrl[redacted]";
    }
}
