package ru.murad.myvpn.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import ru.murad.myvpn.dto.CreatedPayment;
import ru.murad.myvpn.exception.PaymentProviderUncertainException;
import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.model.ProviderPaymentStatus;

import java.net.URI;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreatedPaymentValidatorTest {

    private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");
    private static final URI URL = URI.create(
            "https://example.invalid/fake-pay/abcdefghijklmnop");
    private final CreatedPaymentValidator validator = new CreatedPaymentValidator();

    @Test
    void validPaymentMustPass() {
        assertThatCode(() -> validator.validate(
                PaymentProviderType.FAKE,
                payment("fake-payment", NOW, NOW.plusSeconds(60), URL),
                NOW)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource("invalidPayments")
    void malformedPostCreateResultMustBeUncertain(CreatedPayment payment) {
        assertThatThrownBy(() -> validator.validate(
                PaymentProviderType.FAKE, payment, NOW))
                .isInstanceOf(PaymentProviderUncertainException.class)
                .hasMessageNotContaining("fake-payment")
                .hasMessageNotContaining(URL.toString());
    }

    private static Stream<CreatedPayment> invalidPayments() {
        return Stream.of(
                null,
                payment("", NOW, null, URL),
                payment("x".repeat(129), NOW, null, URL),
                new CreatedPayment("fake-payment", null, URL, NOW, null),
                new CreatedPayment("fake-payment", ProviderPaymentStatus.SUCCEEDED,
                        URL, NOW, null),
                payment("fake-payment", null, null, URL),
                payment("fake-payment", NOW.plusSeconds(301), null, URL),
                payment("fake-payment", NOW, NOW.minusSeconds(1), URL),
                payment("fake-payment", NOW, NOW, URL),
                payment("fake-payment", NOW, NOW.minusSeconds(1), URL),
                payment("fake-payment", NOW, null,
                        URI.create("https://evil.invalid/fake-pay/abcdefghijklmnop")));
    }

    private static CreatedPayment payment(
            String id,
            Instant createdAt,
            Instant expiresAt,
            URI url
    ) {
        return new CreatedPayment(
                id, ProviderPaymentStatus.PENDING, url, createdAt, expiresAt);
    }
}
