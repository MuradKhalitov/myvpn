package ru.murad.myvpn.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Locale;

final class TelegramYooKassaCondition implements Condition {

    @Override
    public boolean matches(
            ConditionContext context,
            AnnotatedTypeMetadata metadata
    ) {
        String configured = context.getEnvironment()
                .getProperty("payment.provider", "");
        return "TELEGRAM_YOOKASSA".equals(configured
                .trim()
                .replace('-', '_')
                .toUpperCase(Locale.ROOT));
    }
}
