package ru.murad.myvpn.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.murad.myvpn.adapter.telegram.TelegramUserIdResolver;
import ru.murad.myvpn.application.vpn.CurrentVpnAccessQuery;
import ru.murad.myvpn.application.vpn.VpnAccessView;
import ru.murad.myvpn.dto.TelegramIncomingMessage;
import ru.murad.myvpn.exception.TelegramUserNotFoundException;
import ru.murad.myvpn.model.VpnAccessStatus;
import ru.murad.myvpn.service.VpnConfigurationCommandService;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
@Slf4j
public class VpnConfigurationCommandServiceImpl
        implements VpnConfigurationCommandService {

    private static final int TELEGRAM_TEXT_LIMIT = 4096;
    private static final String UNAVAILABLE_MESSAGE =
            "Не удалось получить VPN-конфигурацию. Обратитесь в поддержку.";

    private final TelegramUserIdResolver userIdResolver;
    private final CurrentVpnAccessQuery vpnAccessQuery;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public String configurationForOwner(TelegramIncomingMessage message) {
        if (message.chatId() != message.telegramId()) {
            return "Для безопасности запросите VPN-конфигурацию в личном чате с ботом.";
        }

        VpnAccessView access;
        try {
            access = vpnAccessQuery.findCurrent(
                    userIdResolver.resolve(message.telegramId())).orElse(null);
        } catch (TelegramUserNotFoundException notFound) {
            access = null;
        }
        if (access == null) {
            return "У вас пока нет активной VPN-подписки.";
        }
        if (!access.expiresAt().isAfter(clock.instant())) {
            return "Срок действия VPN-подписки истёк.";
        }
        if (access.status() != VpnAccessStatus.ACTIVE
                || access.configuration() == null
                || access.configuration().isBlank()) {
            log.warn("VPN configuration is unavailable for an authenticated owner");
            return UNAVAILABLE_MESSAGE;
        }

        String result = "Ваша VPN-конфигурация:\n" + access.configuration()
                + "\n\nДействует до: "
                + DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC)
                        .format(access.expiresAt());
        return result.length() > TELEGRAM_TEXT_LIMIT
                ? UNAVAILABLE_MESSAGE : result;
    }
}
