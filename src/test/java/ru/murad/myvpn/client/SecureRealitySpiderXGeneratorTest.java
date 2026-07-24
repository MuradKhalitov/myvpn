package ru.murad.myvpn.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecureRealitySpiderXGeneratorTest {

    @Test
    void shouldGenerateOfficialLengthAlphanumericPath() {
        SecureRealitySpiderXGenerator generator =
                new SecureRealitySpiderXGenerator();

        String first = generator.generate();
        String second = generator.generate();

        assertThat(first).matches("/[0-9a-zA-Z]{15}");
        assertThat(second).matches("/[0-9a-zA-Z]{15}");
        assertThat(second).isNotEqualTo(first);
    }
}
