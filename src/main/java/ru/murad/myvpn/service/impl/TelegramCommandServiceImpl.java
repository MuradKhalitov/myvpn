package ru.murad.myvpn.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.murad.myvpn.config.PaymentProperties;
import ru.murad.myvpn.dto.ActivateSubscriptionRequest;
import ru.murad.myvpn.dto.PaymentCheckoutResult;
import ru.murad.myvpn.dto.ProviderPayment;
import ru.murad.myvpn.dto.RegisterTelegramUserRequest;
import ru.murad.myvpn.dto.RevokeSubscriptionRequest;
import ru.murad.myvpn.dto.SubscriptionDto;
import ru.murad.myvpn.dto.TelegramButton;
import ru.murad.myvpn.dto.TelegramCallbackQuery;
import ru.murad.myvpn.dto.TelegramCommandResponse;
import ru.murad.myvpn.dto.TelegramIncomingMessage;
import ru.murad.myvpn.dto.TelegramUserDto;
import ru.murad.myvpn.dto.VpnTariffDto;
import ru.murad.myvpn.exception.AdministratorAccessDeniedException;
import ru.murad.myvpn.exception.PaymentNotFoundException;
import ru.murad.myvpn.exception.FakePaymentStateLostException;
import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.model.PaymentStatus;
import ru.murad.myvpn.model.ProviderPaymentStatus;
import ru.murad.myvpn.repository.PaymentOrderRepository;
import ru.murad.myvpn.repository.TelegramUserRepository;
import ru.murad.myvpn.service.AdminAuthorizationService;
import ru.murad.myvpn.service.FakePaymentControlService;
import ru.murad.myvpn.service.FakePaymentRecoveryService;
import ru.murad.myvpn.client.PaymentConfirmationUrl;
import ru.murad.myvpn.service.PaymentCheckoutService;
import ru.murad.myvpn.service.PaymentVerificationService;
import ru.murad.myvpn.dto.PaymentVerificationOutcome;
import ru.murad.myvpn.dto.PaymentVerificationResult;
import ru.murad.myvpn.service.SubscriptionService;
import ru.murad.myvpn.service.TariffService;
import ru.murad.myvpn.service.TelegramCommandService;
import ru.murad.myvpn.service.UserService;
import ru.murad.myvpn.service.VpnConfigurationCommandService;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class TelegramCommandServiceImpl implements TelegramCommandService {

    @Autowired
    public TelegramCommandServiceImpl(UserService userService, TariffService tariffService,
            SubscriptionService subscriptionService, AdminAuthorizationService adminAuthorizationService,
            PaymentCheckoutService paymentCheckoutService, PaymentVerificationService paymentVerificationService,
            PaymentProperties paymentProperties, TelegramUserRepository telegramUserRepository,
            PaymentOrderRepository paymentOrderRepository, Optional<FakePaymentControlService> fakePaymentControlService,
            Optional<FakePaymentRecoveryService> fakePaymentRecoveryService,
            Optional<VpnConfigurationCommandService> vpnConfigurationCommandService) {
        this.userService = userService; this.tariffService = tariffService; this.subscriptionService = subscriptionService;
        this.adminAuthorizationService = adminAuthorizationService; this.paymentCheckoutService = paymentCheckoutService;
        this.paymentVerificationService = paymentVerificationService; this.paymentProperties = paymentProperties;
        this.telegramUserRepository = telegramUserRepository; this.paymentOrderRepository = paymentOrderRepository;
        this.fakePaymentControlService = fakePaymentControlService; this.fakePaymentRecoveryService = fakePaymentRecoveryService;
        this.vpnConfigurationCommandService = vpnConfigurationCommandService;
    }

    public TelegramCommandServiceImpl(UserService userService, TariffService tariffService,
            SubscriptionService subscriptionService, AdminAuthorizationService adminAuthorizationService,
            PaymentCheckoutService paymentCheckoutService, PaymentProperties paymentProperties,
            TelegramUserRepository telegramUserRepository, PaymentOrderRepository paymentOrderRepository,
            Optional<FakePaymentControlService> fakePaymentControlService,
            Optional<FakePaymentRecoveryService> fakePaymentRecoveryService) {
        this(userService, tariffService, subscriptionService, adminAuthorizationService,
                paymentCheckoutService, null, paymentProperties, telegramUserRepository,
                paymentOrderRepository, fakePaymentControlService, fakePaymentRecoveryService, Optional.empty());
    }

    private static final Pattern TARIFF_CALLBACK_CODE =
            Pattern.compile("[A-Z0-9_-]{1,32}");
    private static final String WELCOME =
            "Добро пожаловать в My VPN!";
    private static final String HELP = """
            Доступные команды:
            /start — регистрация
            /tariffs — доступные тарифы
            /buy — купить подписку
            /subscription — текущая подписка
            /help — помощь""";

    private final UserService userService;
    private final TariffService tariffService;
    private final SubscriptionService subscriptionService;
    private final AdminAuthorizationService adminAuthorizationService;
    private final PaymentCheckoutService paymentCheckoutService;
    private final PaymentVerificationService paymentVerificationService;
    private final PaymentProperties paymentProperties;
    private final TelegramUserRepository telegramUserRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final Optional<FakePaymentControlService> fakePaymentControlService;
    private final Optional<FakePaymentRecoveryService> fakePaymentRecoveryService;
    private final Optional<VpnConfigurationCommandService> vpnConfigurationCommandService;

    @Override
    public String handle(TelegramIncomingMessage message) {
        return handleResponse(message).text();
    }

    @Override
    public TelegramCommandResponse handleResponse(TelegramIncomingMessage message) {
        try {
            String[] parts = message.text().trim().split("\\s+");
            return switch (parts[0].toLowerCase()) {
                case "/buy" -> buy();
                case "/fakepay_success" -> fakePaySuccess(message.telegramId(), parts);
                case "/fakepay_reset" -> fakePayReset(message.telegramId(), parts);
                case "/start" -> TelegramCommandResponse.text(start(message));
                case "/tariffs" -> TelegramCommandResponse.text(tariffs());
                case "/subscription" ->
                        TelegramCommandResponse.text(subscription(message.telegramId()));
                case "/vpn" -> TelegramCommandResponse.text(vpnConfigurationCommandService
                        .map(service -> service.configurationForOwner(message))
                        .orElse("Не удалось получить VPN-конфигурацию. Обратитесь в поддержку."));
                case "/help" -> TelegramCommandResponse.text(HELP);
                case "/activate" ->
                        TelegramCommandResponse.text(activate(message.telegramId(), parts));
                case "/revoke" ->
                        TelegramCommandResponse.text(revoke(message.telegramId(), parts));
                case "/user" ->
                        TelegramCommandResponse.text(user(message.telegramId(), parts));
                default -> TelegramCommandResponse.text(
                        "Неизвестная команда. Используйте /help.");
            };
        } catch (AdministratorAccessDeniedException exception) {
            return TelegramCommandResponse.text("Доступ запрещён.");
        } catch (FakePaymentStateLostException exception) {
            return TelegramCommandResponse.text(
                    "Состояние тестового платежа потеряно после перезапуска. "
                            + "Обратитесь к администратору для /fakepay_reset.");
        } catch (IllegalArgumentException exception) {
            return TelegramCommandResponse.text(
                    "Не удалось выполнить команду: " + exception.getMessage());
        } catch (RuntimeException exception) {
            return TelegramCommandResponse.text(
                    "Не удалось выполнить команду. Повторите попытку позже.");
        }
    }

    @Override
    public TelegramCommandResponse handleCallback(TelegramCallbackQuery callback) {
        try {
            if (callback.data().startsWith("buy:")) {
                String tariffCode = callback.data().substring("buy:".length());
                if (!TARIFF_CALLBACK_CODE.matcher(tariffCode).matches()) {
                    throw new IllegalArgumentException("Некорректный тариф");
                }
                return checkout(paymentCheckoutService.startCheckout(
                        callback.telegramId(), tariffCode));
            }
            if ("payment:check".equals(callback.data())) {
                if (paymentVerificationService == null) {
                    return TelegramCommandResponse.text(formatProviderStatus(
                            paymentCheckoutService.checkCurrentPayment(callback.telegramId())));
                }
                return TelegramCommandResponse.text(formatVerificationStatus(
                        paymentVerificationService.verifyCurrentPayment(callback.telegramId())));
            }
            return TelegramCommandResponse.text("Неизвестное действие.");
        } catch (FakePaymentStateLostException exception) {
            return TelegramCommandResponse.text(
                    "Состояние тестового платежа потеряно после перезапуска. "
                            + "Обратитесь к администратору для /fakepay_reset.");
        } catch (IllegalArgumentException exception) {
            return TelegramCommandResponse.text(
                    "Не удалось выполнить действие: " + exception.getMessage());
        } catch (RuntimeException exception) {
            return TelegramCommandResponse.text(
                    "Не удалось выполнить действие. Повторите попытку позже.");
        }
    }

    private String start(TelegramIncomingMessage message) {
        userService.register(new RegisterTelegramUserRequest(
                message.telegramId(), message.chatId(), message.username(),
                message.firstName(), message.lastName()));
        return WELCOME + "\n\n" + HELP;
    }

    private TelegramCommandResponse buy() {
        List<VpnTariffDto> tariffs = tariffService.findAvailableTariffs();
        if (tariffs.isEmpty()) {
            return TelegramCommandResponse.text("Сейчас нет доступных тарифов.");
        }
        List<List<TelegramButton>> keyboard = tariffs.stream()
                .map(tariff -> List.of(TelegramButton.callback(
                        tariff.name() + " — " + tariff.price().toPlainString() + " RUB",
                        "buy:" + tariff.code())))
                .toList();
        return new TelegramCommandResponse("Выберите тариф:", keyboard);
    }

    private TelegramCommandResponse checkout(PaymentCheckoutResult checkout) {
        PaymentConfirmationUrl.fake(checkout.confirmationUrl());
        String text = "Тариф: " + checkout.tariffName()
                + "\nСтоимость: " + checkout.amount().toPlainString()
                + " " + checkout.currency()
                + "\nСрок: " + checkout.durationDays() + " дней"
                + "\n\nНажмите кнопку для оплаты.";
        return new TelegramCommandResponse(text, List.of(
                List.of(TelegramButton.url(
                        "Оплатить", checkout.confirmationUrl().toString())),
                List.of(TelegramButton.callback(
                        "Проверить оплату", "payment:check"))));
    }

    private TelegramCommandResponse fakePayReset(
            long administratorId,
            String[] parts
    ) {
        adminAuthorizationService.checkAccess(administratorId);
        if (paymentProperties.provider() != PaymentProviderType.FAKE
                || fakePaymentRecoveryService.isEmpty()) {
            throw new IllegalArgumentException("Fake payment provider is disabled");
        }
        requireArguments(parts, 2, "/fakepay_reset <telegramId>");
        fakePaymentRecoveryService.get().resetLostPayment(
                administratorId, Long.parseLong(parts[1]));
        return TelegramCommandResponse.text(
                "Потерянный fake-платёж закрыт. Можно создать новый checkout.");
    }

    private TelegramCommandResponse fakePaySuccess(
            long administratorId,
            String[] parts
    ) {
        adminAuthorizationService.checkAccess(administratorId);
        if (paymentProperties.provider() != PaymentProviderType.FAKE
                || fakePaymentControlService.isEmpty()) {
            throw new IllegalArgumentException("Fake payment provider is disabled");
        }
        requireArguments(parts, 2, "/fakepay_success <telegramId>");
        long targetTelegramId = Long.parseLong(parts[1]);
        var user = telegramUserRepository.findByTelegramId(targetTelegramId)
                .orElseThrow(PaymentNotFoundException::new);
        var order = paymentOrderRepository.findOpenByUser(user.getId())
                .filter(candidate -> candidate.getStatus() == PaymentStatus.PENDING)
                .orElseThrow(PaymentNotFoundException::new);
        if (order.getProvider() != PaymentProviderType.FAKE) {
            throw new IllegalArgumentException(
                    "Команда доступна только для fake-платежей");
        }
        fakePaymentControlService.get().markSucceeded(order.getProviderPaymentId());
        if (paymentProperties.provider() == PaymentProviderType.FAKE) {
            return TelegramCommandResponse.text(
                    "Fake payment имеет статус SUCCEEDED у провайдера.\n"
                            + "Нажмите «Проверить оплату», чтобы бот подтвердил платёж.");
        }
        return TelegramCommandResponse.text(
                "Fake payment имеет статус SUCCEEDED у провайдера.");
    }

    private String formatProviderStatus(ProviderPayment payment) {
        if (payment.status() == ProviderPaymentStatus.SUCCEEDED) {
            return "Оплата подтверждена, обработка будет добавлена следующим этапом";
        }
        if (payment.status() == ProviderPaymentStatus.CANCELED) {
            return "Платёж отменён";
        }
        return "Ожидает оплаты";
    }

    private String formatVerificationStatus(PaymentVerificationResult result) {
        return switch (result.outcome()) {
            case SUCCEEDED, ALREADY_SUCCEEDED -> formatProviderStatus(new ProviderPayment("x", ProviderPaymentStatus.SUCCEEDED,
                    true, java.math.BigDecimal.ONE, "RUB", "fake", null, null, java.time.Instant.EPOCH, java.time.Instant.EPOCH))
                    + " Подписка ожидает активации.";
            case CANCELED, ALREADY_CANCELED -> "РџР»Р°С‚РµР¶ РѕС‚РјРµРЅС‘РЅ.";
            case TOO_EARLY -> "РџСЂРѕРІРµСЂРєР° СѓР¶Рµ РІС‹РїРѕР»РЅСЏР»Р°СЃСЊ. РџРѕРїСЂРѕР±СѓР№С‚Рµ РЅРµРјРЅРѕРіРѕ РїРѕР·Р¶Рµ.";
            case MANUAL_REVIEW_REQUIRED -> "РџР»Р°С‚С‘Р¶ С‚СЂРµР±СѓРµС‚ СЂСѓС‡РЅРѕР№ РїСЂРѕРІРµСЂРєРё.";
            case PROVIDER_UNAVAILABLE, PROVIDER_RESULT_UNCERTAIN -> "РќРµ СѓРґР°Р»РѕСЃСЊ РїСЂРѕРІРµСЂРёС‚СЊ РїР»Р°С‚С‘Р¶. РџРѕРїСЂРѕР±СѓР№С‚Рµ РїРѕР·Р¶Рµ.";
            case CHECKOUT_INCOMPLETE -> "РЎРѕР·РґР°РЅРёРµ РїР»Р°С‚РµР¶Р° РµС‰С‘ РЅРµ Р·Р°РІРµСЂС€РµРЅРѕ.";
            case STILL_PENDING -> formatProviderStatus(new ProviderPayment("x", ProviderPaymentStatus.PENDING,
                    false, java.math.BigDecimal.ONE, "RUB", "fake", null, null, java.time.Instant.EPOCH, null));
            case NOT_FOUND -> "РџР»Р°С‚С‘Р¶ РЅРµ РЅР°Р№РґРµРЅ.";
            case TERMINAL -> "Р­С‚РѕС‚ РїР»Р°С‚С‘Р¶ Р±РѕР»СЊС€Рµ РЅРµР»СЊР·СЏ РїСЂРѕРІРµСЂРёС‚СЊ.";
            case AMBIGUOUS_PAYMENT_STATE -> "РћР±РЅР°СЂСѓР¶РµРЅРѕ РЅРµСЃРєРѕР»СЊРєРѕ РЅРµР·Р°РІРµСЂС€С‘РЅРЅС‹С… РїР»Р°С‚РµР¶РµР№. РўСЂРµР±СѓРµС‚СЃСЏ СЂСѓС‡РЅР°СЏ РїСЂРѕРІРµСЂРєР°.";
        };
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
                .orElseThrow(() -> new IllegalArgumentException(
                        "Пользователь не найден"));
        return "Пользователь " + user.telegramId()
                + (user.username() == null ? "" : " @" + user.username())
                + "\n" + subscription(telegramId);
    }

    private String formatSubscription(SubscriptionDto subscription) {
        String configuration = subscription.configurationData() == null
                ? "Конфигурация недоступна"
                : "Configuration available";
        return "Тариф: " + subscription.tariff().name()
                + "\nДействует до: " + subscription.expiresAt()
                + "\nПровайдер: " + subscription.providerName()
                + "\nConfiguration: " + configuration
                + "\nFor configuration use /vpn in a private chat.";
    }

    private void requireArguments(String[] parts, int count, String usage) {
        if (parts.length != count) {
            throw new IllegalArgumentException("Формат: " + usage);
        }
    }
}
