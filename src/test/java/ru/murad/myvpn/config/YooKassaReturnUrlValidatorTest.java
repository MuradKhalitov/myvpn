package ru.murad.myvpn.config;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YooKassaReturnUrlValidatorTest {
    @Test
    void acceptsAbsoluteHttpsReturnUrl() {
        assertThatCode(() -> YooKassaReturnUrlValidator.validate(
                URI.create("https://payments.example.test/yookassa/return?source=app")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnsafeReturnUrls() {
        for (String value : new String[] {"http://payments.example.test/return", "https://user@payments.example.test/return",
                "https://payments.example.test/return#fragment", "/relative"}) {
            assertThatThrownBy(() -> YooKassaReturnUrlValidator.validate(URI.create(value)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
