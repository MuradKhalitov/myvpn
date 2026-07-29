package ru.murad.myvpn.controller;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import ru.murad.myvpn.config.TelegramPaymentProperties;
import ru.murad.myvpn.config.TelegramProperties;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramBotApiPaymentGatewayContextTest {

    @Test
    void createsGatewayWithCanonicalEnvironmentProviderValue() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(new MockEnvironment()
                    .withProperty("payment.provider", "TELEGRAM_YOOKASSA"));
            context.registerBean(TelegramProperties.class,
                    () -> new TelegramProperties(Set.of(), "test-bot-token"));
            context.registerBean(TelegramPaymentProperties.class,
                    () -> new TelegramPaymentProperties(
                            "test-provider-token", "RUB",
                            Duration.ofSeconds(9), false, "", ""));
            context.register(TelegramBotApiPaymentGateway.class);

            context.refresh();

            assertThat(context.getBean(TelegramBotApiPaymentGateway.class))
                    .isNotNull();
        }
    }
}
