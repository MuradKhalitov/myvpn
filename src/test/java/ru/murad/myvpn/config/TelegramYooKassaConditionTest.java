package ru.murad.myvpn.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TelegramYooKassaConditionTest {

    private final TelegramYooKassaCondition condition =
            new TelegramYooKassaCondition();

    @ParameterizedTest
    @ValueSource(strings = {
            "TELEGRAM_YOOKASSA",
            "telegram-yookassa",
            "telegram_yookassa"
    })
    void acceptsEnvironmentAndYamlProviderSpellings(String value) {
        ConditionContext context = mock(ConditionContext.class);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("payment.provider", value);
        when(context.getEnvironment()).thenReturn(environment);

        assertThat(condition.matches(
                context, mock(AnnotatedTypeMetadata.class))).isTrue();
    }
}
