package ru.murad.myvpn.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.murad.myvpn.dto.TelegramIncomingMessage;
import ru.murad.myvpn.model.*;
import ru.murad.myvpn.repository.SubscriptionRepository;
import ru.murad.myvpn.repository.VpnAccessRepository;
import ru.murad.myvpn.service.VpnConfigurationCommandService;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;

@Service @RequiredArgsConstructor @Slf4j
public class VpnConfigurationCommandServiceImpl implements VpnConfigurationCommandService {
    private final SubscriptionRepository subscriptions; private final VpnAccessRepository accesses; private final Clock clock;
    @Override @Transactional(readOnly = true) public String configurationForOwner(TelegramIncomingMessage message) {
        if (message.chatId() != message.telegramId()) return "Для безопасности запросите VPN-конфигурацию в личном чате с ботом.";
        Subscription latest = subscriptions.findFirstByUserTelegramIdAndStatusOrderByExpiresAtDesc(message.telegramId(), SubscriptionStatus.ACTIVE).orElse(null);
        if (latest == null) return "У вас пока нет активной VPN-подписки.";
        if (!latest.getExpiresAt().isAfter(clock.instant())) return "Срок действия VPN-подписки истёк.";
        VpnAccess access = accesses.findBySubscriptionId(latest.getId()).orElse(null);
        if (access == null || access.getStatus() != VpnAccessStatus.ACTIVE || access.getConfigurationData() == null || access.getConfigurationData().isBlank()) {
            log.warn("VPN configuration is unavailable for an authenticated owner");
            return "Не удалось получить VPN-конфигурацию. Обратитесь в поддержку.";
        }
        String result = "Ваша VPN-конфигурация:\n" + access.getConfigurationData() + "\n\nДействует до: "
                + DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(latest.getExpiresAt());
        if (result.length() > 4096) return "Не удалось получить VPN-конфигурацию. Обратитесь в поддержку.";
        return result;
    }
}
