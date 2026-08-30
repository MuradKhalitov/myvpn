package ru.murad.myvpn.application.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailNormalizerTest {

    private final EmailNormalizer normalizer = new EmailNormalizer();

    @Test
    void trimsAndLowerCasesUsingCanonicalForm() {
        assertThat(normalizer.normalize("  User.Name@Example.COM  "))
                .isEqualTo("user.name@example.com");
    }

    @Test
    void rejectsInvalidStructureControlCharactersAndOversizedValue() {
        assertThatThrownBy(() -> normalizer.normalize("not-an-email"))
                .isInstanceOf(InvalidEmailException.class);
        assertThatThrownBy(() -> normalizer.normalize("user\n@example.com"))
                .isInstanceOf(InvalidEmailException.class);
        assertThatThrownBy(() -> normalizer.normalize("a".repeat(310) + "@example.com"))
                .isInstanceOf(InvalidEmailException.class);
    }
}
