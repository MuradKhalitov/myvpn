package ru.murad.myvpn.application.auth;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class PhoneNormalizerTest {
    private final PhoneNormalizer normalizer = new PhoneNormalizer();
    @ParameterizedTest @ValueSource(strings = {"+79991234567", "79991234567", "89991234567", "+7 (999) 123-45-67"})
    void normalizesRussianPhone(String input) { assertThat(normalizer.normalize(input)).isEqualTo("+79991234567"); }
    @ParameterizedTest @ValueSource(strings = {"+7999123456", "+712345678901", "abc", ""})
    void rejectsInvalidPhone(String input) { assertThatThrownBy(() -> normalizer.normalize(input)).isInstanceOf(InvalidAuthenticationException.class); }
}
