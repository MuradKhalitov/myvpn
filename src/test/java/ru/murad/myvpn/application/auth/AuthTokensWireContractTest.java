package ru.murad.myvpn.application.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AuthTokensWireContractTest {
    @Test void serverSerializationMatchesTheAndroidHttpRegressionFixture() throws Exception {
        var mapper = new ObjectMapper();
        var actual = mapper.readTree(mapper.writeValueAsString(
                new AuthTokens("access-b", "credential-b", Duration.ofMinutes(15))));
        var expected = mapper.readTree(Files.readString(Path.of(
                "android/app/src/test/resources/refresh-success.json")));
        assertThat(actual).isEqualTo(expected);
        assertThat(actual.has("accountId")).isFalse();
    }
}
