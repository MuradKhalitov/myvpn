package ru.murad.myvpn.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.murad.myvpn.dto.ActivateSubscriptionRequest;
import ru.murad.myvpn.dto.RegisterTelegramUserRequest;
import ru.murad.myvpn.dto.RevokeSubscriptionRequest;
import ru.murad.myvpn.dto.SubscriptionDto;
import ru.murad.myvpn.dto.TelegramIncomingMessage;
import ru.murad.myvpn.dto.TelegramUserDto;
import ru.murad.myvpn.dto.VpnTariffDto;
import ru.murad.myvpn.exception.AdministratorAccessDeniedException;
import ru.murad.myvpn.service.AdminAuthorizationService;
import ru.murad.myvpn.service.SubscriptionService;
import ru.murad.myvpn.service.TariffService;
import ru.murad.myvpn.service.TelegramCommandService;
import ru.murad.myvpn.service.UserService;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TelegramCommandServiceImpl implements TelegramCommandService {

    private static final String WELCOME = """
            Добро пожаловать в My VPN! Здесь вы можете посмотреть тарифы,
            проверить подписку и получить данные для подключения""";
    private static final String HELP = """
            Доступные команды:
            /start — регистрация
            /tariffs — доступные тарифы
            /subscription — текущая подписка
            /help — помощь""";

    private final UserService userService;
    private final TariffService tariffService;
    private final SubscriptionService subscriptionService;
    private final AdminAuthorizationService adminAuthorizationService;

    @Override
    public String handle(TelegramIncomingMessage message) {
        try {
            String[] parts = message.text().trim().split("\\s+");
            return switch (parts[0].toLowerCase()) {
                case "/start" -> start(message);
                case "/tariffs" -> tariffs();
                case "/subscription" -> subscription(message.telegramId());
                case "/help" -> HELP;
                case "/activate" -> activate(message.telegramId(), parts);
                case "/revoke" -> revoke(message.telegramId(), parts);
                case "/user" -> user(message.telegramId(), parts);
                default -> "Неизвестная команда. Используйте /help.";
            };
        } catch (AdministratorAccessDeniedException exception) {
            return "Доступ запрещён.";
        } catch (IllegalArgumentException exception) {
            return "Не удалось выполнить команду: " + exception.getMessage();
        } catch (RuntimeException exception) {
            return "Не удалось выполнить команду. Повторите попытку позже.";
        }
    }

    private String start(TelegramIncomingMessage message) {
        userService.register(new RegisterTelegramUserRequest(
                message.telegramId(), message.chatId(), message.username(),
                message.firstName(), message.lastName()));
        return WELCOME + "\n\n" + HELP;
    }

    private String tariffs() {
        List<VpnTariffDto> tariffs = tariffService.findAvailableTariffs();
        if (tariffs.isEmpty()) {
            return "Сейчас нет доступных тарифов.";
        }
        StringBuilder response = new StringBuilder("Доступные тарифы:");
        tariffs.forEach(tariff -> response.append("\n\n")
                .append(tariff.code()).append(" — ").append(tariff.name())
                .append("\n").append(tariff.description())
                .append("\n").append(tariff.price().toPlainString())
                .append(" ").append(tariff.currency()));
        return response.toString();
    }

    private String subscription(long telegramId) {
        return subscriptionService.findCurrent(telegramId)
                .map(this::formatSubscription)
                .orElse("Активная подписка не найдена.");
    }

    private String activate(long administratorId, String[] parts) {
        requireArguments(parts, 3, "/activate <telegramId> <tariffCode>");
        SubscriptionDto subscription = subscriptionService.activate(
                new ActivateSubscriptionRequest(
                        administratorId, Long.parseLong(parts[1]), parts[2]));
        return "Подписка активирована до " + subscription.expiresAt() + ".";
    }

    private String revoke(long administratorId, String[] parts) {
        requireArguments(parts, 2, "/revoke <telegramId>");
        subscriptionService.revoke(new RevokeSubscriptionRequest(
                administratorId, Long.parseLong(parts[1])));
        return "Подписка отозвана.";
    }

    private String user(long administratorId, String[] parts) {
        requireArguments(parts, 2, "/user <telegramId>");
        adminAuthorizationService.checkAccess(administratorId);
        long telegramId = Long.parseLong(parts[1]);
        TelegramUserDto user = userService.findByTelegramId(telegramId)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден"));
        return "Пользователь " + user.telegramId()
                + (user.username() == null ? "" : " @" + user.username())
                + "\n" + subscription(telegramId);
    }

    private String formatSubscription(SubscriptionDto subscription) {
        String configuration = subscription.configurationData() == null
                ? "Конфигурация недоступна"
                : subscription.configurationData();
        return "Тариф: " + subscription.tariff().name()
                + "\nДействует до: " + subscription.expiresAt()
                + "\nПровайдер: " + subscription.providerName()
                + "\nКонфигурация:\n" + configuration;
    }

    private void requireArguments(String[] parts, int count, String usage) {
        if (parts.length != count) {
            throw new IllegalArgumentException("Формат: " + usage);
        }
    }
}
