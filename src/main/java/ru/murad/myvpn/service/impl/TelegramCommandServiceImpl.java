package ru.murad.myvpn.service.impl;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.murad.myvpn.client.PaymentConfirmationUrl;
import ru.murad.myvpn.config.PaymentProperties;
import ru.murad.myvpn.dto.*;
import ru.murad.myvpn.exception.AdministratorAccessDeniedException;
import ru.murad.myvpn.exception.FakePaymentStateLostException;
import ru.murad.myvpn.exception.PaymentNotFoundException;
import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.model.PaymentStatus;
import ru.murad.myvpn.model.ProviderPaymentStatus;
import ru.murad.myvpn.repository.PaymentOrderRepository;
import ru.murad.myvpn.repository.TelegramUserRepository;
import ru.murad.myvpn.service.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class TelegramCommandServiceImpl implements TelegramCommandService {

    private static final Pattern TARIFF_CALLBACK_CODE = Pattern.compile("[A-Z0-9_-]{1,32}");
    private static final String WELCOME = "Добро пожаловать в My VPN!";
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

    @Autowired
    public TelegramCommandServiceImpl(
            UserService userService,
            TariffService tariffService,
            SubscriptionService subscriptionService,
            AdminAuthorizationService adminAuthorizationService,
            PaymentCheckoutService paymentCheckoutService,
            PaymentVerificationService paymentVerificationService,
            PaymentProperties paymentProperties,
            TelegramUserRepository telegramUserRepository,
            PaymentOrderRepository paymentOrderRepository,
            Optional<FakePaymentControlService> fakePaymentControlService,
            Optional<FakePaymentRecoveryService> fakePaymentRecoveryService,
            Optional<VpnConfigurationCommandService> vpnConfigurationCommandService) {
        this.userService = userService;
        this.tariffService = tariffService;
        this.subscriptionService = subscriptionService;
        this.adminAuthorizationService = adminAuthorizationService;
        this.paymentCheckoutService = paymentCheckoutService;
        this.paymentVerificationService = paymentVerificationService;
        this.paymentProperties = paymentProperties;
        this.telegramUserRepository = telegramUserRepository;
        this.paymentOrderRepository = paymentOrderRepository;
        this.fakePaymentControlService = fakePaymentControlService;
        this.fakePaymentRecoveryService = fakePaymentRecoveryService;
        this.vpnConfigurationCommandService = vpnConfigurationCommandService;
    }

    public TelegramCommandServiceImpl(
            UserService userService,
            TariffService tariffService,
            SubscriptionService subscriptionService,
            AdminAuthorizationService adminAuthorizationService,
            PaymentCheckoutService paymentCheckoutService,
            PaymentProperties paymentProperties,
            TelegramUserRepository telegramUserRepository,
            PaymentOrderRepository paymentOrderRepository,
            Optional<FakePaymentControlService> fakePaymentControlService,
            Optional<FakePaymentRecoveryService> fakePaymentRecoveryService) {
        this(userService, tariffService, subscriptionService, adminAuthorizationService,
                paymentCheckoutService, null, paymentProperties, telegramUserRepository,
                paymentOrderRepository, fakePaymentControlService, fakePaymentRecoveryService,
                Optional.empty());
    }

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
                case "/subscription" -> TelegramCommandResponse.text(subscription(message.telegramId()));
                case "/vpn" -> TelegramCommandResponse.text(vpnConfigurationCommandService
                        .map(service -> service.configurationForOwner(message))
                        .orElse("Не удалось получить VPN-конфигурацию. Обратитесь в поддержку."));
                case "/help" -> TelegramCommandResponse.text(HELP);
                case "/activate" -> TelegramCommandResponse.text(activate(message.telegramId(), parts));
                case "/revoke" -> TelegramCommandResponse.text(revoke(message.telegramId(), parts));
                case "/user" -> TelegramCommandResponse.text(user(message.telegramId(), parts));
                default -> TelegramCommandResponse.text("Неизвестная команда. Используйте /help.");
            };
        } catch (AdministratorAccessDeniedException exception) {
            return TelegramCommandResponse.text("Доступ запрещён.");
        } catch (FakePaymentStateLostException exception) {
            return TelegramCommandResponse.text("Состояние тестового платежа потеряно после перезапуска. "
                    + "Обратитесь к администратору для /fakepay_reset.");
        } catch (IllegalArgumentException exception) {
            return TelegramCommandResponse.text("Не удалось выполнить команду: " + exception.getMessage());
        } catch (RuntimeException exception) {
            return TelegramCommandResponse.text("Не удалось выполнить команду. Повторите попытку позже.");
        }
    }

    @Override
    public TelegramCommandResponse handleCallback(TelegramCallbackQuery callback) {
        try {
            if (callback.data().startsWith("buy:")) {
                String code = callback.data().substring(4);
                if (!TARIFF_CALLBACK_CODE.matcher(code).matches()) {
                    throw new IllegalArgumentException("Некорректный тариф");
                }
                return checkout(paymentCheckoutService.startCheckout(callback.telegramId(), code));
            }
            if ("payment:check".equals(callback.data())) {
                return TelegramCommandResponse.text(paymentVerificationService == null
                        ? formatProviderStatus(paymentCheckoutService.checkCurrentPayment(callback.telegramId()))
                        : formatVerificationStatus(paymentVerificationService.verifyCurrentPayment(callback.telegramId())));
            }
            return TelegramCommandResponse.text("Неизвестное действие.");
        } catch (FakePaymentStateLostException exception) {
            return TelegramCommandResponse.text("Состояние тестового платежа потеряно после перезапуска. "
                    + "Обратитесь к администратору для /fakepay_reset.");
        } catch (IllegalArgumentException exception) {
            return TelegramCommandResponse.text("Не удалось выполнить действие: " + exception.getMessage());
        } catch (RuntimeException exception) {
            return TelegramCommandResponse.text("Не удалось выполнить действие. Повторите попытку позже.");
        }
    }

    private String start(TelegramIncomingMessage message) {
        userService.register(new RegisterTelegramUserRequest(
                message.telegramId(), message.chatId(), message.username(), message.firstName(), message.lastName()));
        return WELCOME + "\n\n" + HELP;
    }

    private TelegramCommandResponse buy() {
        List<VpnTariffDto> tariffs = tariffService.findAvailableTariffs();
        if (tariffs.isEmpty()) {
            return TelegramCommandResponse.text("Сейчас нет доступных тарифов.");
        }
        return new TelegramCommandResponse("Выберите тариф:", tariffs.stream()
                .map(tariff -> List.of(TelegramButton.callback(
                        tariff.name() + " — " + tariff.price().toPlainString() + " RUB",
                        "buy:" + tariff.code())))
                .toList());
    }

    private TelegramCommandResponse checkout(PaymentCheckoutResult checkout) {
        if (checkout.destination() instanceof CheckoutDestination.TelegramInvoiceSent) {
            return TelegramCommandResponse.text(
                    "Счёт на тариф «" + checkout.tariffName()
                            + "» отправлен в этот чат. Оплатите его средствами Telegram.");
        }
        var redirect = (CheckoutDestination.RedirectUrl) checkout.destination();
        if (paymentProperties.provider() == PaymentProviderType.FAKE) {
            PaymentConfirmationUrl.fake(redirect.url());
        }
        String text = "Тариф: " + checkout.tariffName()
                + "\nСтоимость: " + checkout.amount().toPlainString() + " " + checkout.currency()
                + "\nСрок: " + checkout.durationDays() + " дней"
                + "\n\nНажмите кнопку для оплаты.";
        return new TelegramCommandResponse(text, List.of(
                List.of(TelegramButton.url("Оплатить", redirect.url().toString())),
                List.of(TelegramButton.callback("Проверить оплату", "payment:check"))));
    }

    private TelegramCommandResponse fakePayReset(long adminTelegramId, String[] parts) {
        adminAuthorizationService.checkAccess(adminTelegramId);
        if (paymentProperties.provider() != PaymentProviderType.FAKE || fakePaymentRecoveryService.isEmpty()) {
            throw new IllegalArgumentException("Fake payment provider is disabled");
        }
        requireArguments(parts, 2, "/fakepay_reset <telegramId>");
        fakePaymentRecoveryService.get().resetLostPayment(adminTelegramId, Long.parseLong(parts[1]));
        return TelegramCommandResponse.text("Потерянный fake-платёж закрыт. Можно создать новый checkout.");
    }

    private TelegramCommandResponse fakePaySuccess(long adminTelegramId, String[] parts) {
        adminAuthorizationService.checkAccess(adminTelegramId);
        if (paymentProperties.provider() != PaymentProviderType.FAKE || fakePaymentControlService.isEmpty()) {
            throw new IllegalArgumentException("Fake payment provider is disabled");
        }
        requireArguments(parts, 2, "/fakepay_success <telegramId>");
        var user = telegramUserRepository.findByTelegramId(Long.parseLong(parts[1]))
                .orElseThrow(PaymentNotFoundException::new);
        var order = paymentOrderRepository.findOpenByUser(user.getId())
                .filter(candidate -> candidate.getStatus() == PaymentStatus.PENDING)
                .orElseThrow(PaymentNotFoundException::new);
        if (order.getProvider() != PaymentProviderType.FAKE) {
            throw new IllegalArgumentException("Команда доступна только для fake-платежей");
        }
        fakePaymentControlService.get().markSucceeded(order.getProviderPaymentId());
        return TelegramCommandResponse.text("Fake payment имеет статус SUCCEEDED у провайдера.\n"
                + "Нажмите «Проверить оплату», чтобы бот подтвердил платёж.");
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
            case SUCCEEDED, ALREADY_SUCCEEDED -> formatProviderStatus(new ProviderPayment(
                    "x", ProviderPaymentStatus.SUCCEEDED, true, BigDecimal.ONE, "RUB", "fake",
                    null, null, Instant.EPOCH, Instant.EPOCH)) + " Подписка ожидает активации.";
            case CANCELED, ALREADY_CANCELED -> "Платёж отменён.";
            case TOO_EARLY -> "Проверка уже выполнялась. Попробуйте немного позже.";
            case MANUAL_REVIEW_REQUIRED -> "Платёж требует ручной проверки.";
            case PROVIDER_UNAVAILABLE, PROVIDER_RESULT_UNCERTAIN -> "Не удалось проверить платёж. Попробуйте позже.";
            case CHECKOUT_INCOMPLETE -> "Создание платежа ещё не завершено.";
            case STILL_PENDING -> formatProviderStatus(new ProviderPayment(
                    "x", ProviderPaymentStatus.PENDING, false, BigDecimal.ONE, "RUB", "fake",
                    null, null, Instant.EPOCH, null));
            case NOT_FOUND -> "Платёж не найден.";
            case TERMINAL -> "Этот платёж больше нельзя проверить.";
            case AMBIGUOUS_PAYMENT_STATE -> "Обнаружено несколько незавершённых платежей. Требуется ручная проверка.";
        };
    }

    private String tariffs() {
        List<VpnTariffDto> tariffs = tariffService.findAvailableTariffs();
        if (tariffs.isEmpty()) {
            return "Сейчас нет доступных тарифов.";
        }
        StringBuilder response = new StringBuilder("Доступные тарифы:");
        tariffs.forEach(tariff -> response.append("\n\n").append(tariff.code())
                .append(" — ").append(tariff.name()).append("\n").append(tariff.description())
                .append("\n").append(tariff.price().toPlainString()).append(" ").append(tariff.currency()));
        return response.toString();
    }

    private String subscription(long telegramId) {
        return subscriptionService.findCurrent(telegramId)
                .map(this::formatSubscription)
                .orElse("Активная подписка не найдена.");
    }

    private String activate(long adminTelegramId, String[] parts) {
        requireArguments(parts, 3, "/activate <telegramId> <tariffCode>");
        SubscriptionDto subscription = subscriptionService.activate(new ActivateSubscriptionRequest(
                adminTelegramId, Long.parseLong(parts[1]), parts[2]));
        return "Подписка активирована до " + subscription.expiresAt() + ".";
    }

    private String revoke(long adminTelegramId, String[] parts) {
        requireArguments(parts, 2, "/revoke <telegramId>");
        subscriptionService.revoke(new RevokeSubscriptionRequest(adminTelegramId, Long.parseLong(parts[1])));
        return "Подписка отозвана.";
    }

    private String user(long adminTelegramId, String[] parts) {
        requireArguments(parts, 2, "/user <telegramId>");
        adminAuthorizationService.checkAccess(adminTelegramId);
        long telegramId = Long.parseLong(parts[1]);
        TelegramUserDto user = userService.findByTelegramId(telegramId)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден"));
        return "Пользователь " + user.telegramId() + (user.username() == null ? "" : " @" + user.username())
                + "\n" + subscription(telegramId);
    }

    private String formatSubscription(SubscriptionDto subscription) {
        String configuration = subscription.configurationData() == null
                ? "Конфигурация недоступна" : "Конфигурация доступна";
        return "Тариф: " + subscription.tariff().name()
                + "\nДействует до: " + subscription.expiresAt()
                + "\nПровайдер: " + subscription.providerName()
                + "\nКонфигурация: " + configuration
                + "\nДля получения конфигурации используйте /vpn в личном чате.";
    }

    private void requireArguments(String[] parts, int count, String usage) {
        if (parts.length != count) {
            throw new IllegalArgumentException("Формат: " + usage);
        }
    }
}
