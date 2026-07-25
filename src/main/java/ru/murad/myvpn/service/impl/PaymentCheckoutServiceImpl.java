package ru.murad.myvpn.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.murad.myvpn.config.PaymentProperties;
import ru.murad.myvpn.dto.CreatePaymentCommand;
import ru.murad.myvpn.dto.CreatedPayment;
import ru.murad.myvpn.dto.PaymentCheckoutResult;
import ru.murad.myvpn.dto.PreparedCheckout;
import ru.murad.myvpn.dto.ProviderPayment;
import ru.murad.myvpn.exception.FakePaymentStateLostException;
import ru.murad.myvpn.exception.OpenPaymentOrderAlreadyExistsException;
import ru.murad.myvpn.exception.PaymentNotFoundException;
import ru.murad.myvpn.exception.PaymentProviderPermanentException;
import ru.murad.myvpn.exception.PaymentProviderUncertainException;
import ru.murad.myvpn.exception.TelegramUserNotFoundException;
import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.model.PaymentStatus;
import ru.murad.myvpn.repository.PaymentOrderRepository;
import ru.murad.myvpn.repository.TelegramUserRepository;
import ru.murad.myvpn.service.PaymentCheckoutService;
import ru.murad.myvpn.service.PaymentCheckoutTransactionService;
import ru.murad.myvpn.service.PaymentProviderRegistry;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PaymentCheckoutServiceImpl implements PaymentCheckoutService {

    private final TelegramUserRepository userRepository;
    private final PaymentOrderRepository orderRepository;
    private final PaymentProviderRegistry providerRegistry;
    private final PaymentCheckoutTransactionService transactionService;
    private final PaymentProperties properties;
    private final Clock clock;

    @Override
    public PaymentCheckoutResult startCheckout(long telegramUserId, String tariffCode) {
        PreparedCheckout prepared = prepareWithConcurrentInsertRecovery(
                telegramUserId, tariffCode, clock.instant());
        if (prepared.status() == PaymentStatus.PENDING) {
            return result(prepared);
        }

        CreatedPayment created;
        try {
            created = providerRegistry.resolve(prepared.provider())
                    .createPayment(command(prepared));
        } catch (PaymentProviderUncertainException uncertain) {
            throw uncertain;
        } catch (PaymentProviderPermanentException permanent) {
            transactionService.markPermanentFailure(prepared, clock.instant());
            throw permanent;
        }
        return transactionService.applyCreatedPayment(
                prepared, created, clock.instant());
    }

    @Override
    public ProviderPayment checkCurrentPayment(long telegramUserId) {
        var user = userRepository.findByTelegramId(telegramUserId)
                .orElseThrow(() -> new TelegramUserNotFoundException(telegramUserId));
        var order = orderRepository.findOpenByUser(user.getId())
                .filter(candidate -> candidate.getStatus() == PaymentStatus.PENDING
                        || candidate.getStatus() == PaymentStatus.CREATING)
                .orElseThrow(PaymentNotFoundException::new);
        if (order.getProviderPaymentId() == null) {
            throw new PaymentNotFoundException();
        }
        ProviderPayment payment;
        try {
            payment = providerRegistry.resolve(order.getProvider())
                    .getPayment(order.getProviderPaymentId());
        } catch (PaymentNotFoundException notFound) {
            if (order.getProvider() == PaymentProviderType.FAKE) {
                throw new FakePaymentStateLostException();
            }
            throw notFound;
        }
        if (payment == null
                || !order.getProviderPaymentId().equals(payment.providerPaymentId())) {
            throw new PaymentProviderUncertainException(
                    "Payment provider returned mismatched payment identity");
        }
        return payment;
    }

    private PreparedCheckout prepareWithConcurrentInsertRecovery(
            long telegramUserId,
            String tariffCode,
            Instant now
    ) {
        try {
            return transactionService.prepareCheckout(
                    telegramUserId, tariffCode, properties.provider(),
                    properties.pendingTtl(), now);
        } catch (OpenPaymentOrderAlreadyExistsException race) {
            return transactionService.prepareCheckout(
                    telegramUserId, tariffCode, properties.provider(),
                    properties.pendingTtl(), now);
        }
    }

    private CreatePaymentCommand command(PreparedCheckout prepared) {
        return new CreatePaymentCommand(
                prepared.orderId(), prepared.idempotenceKey(),
                prepared.amount(), prepared.currency(),
                "VPN subscription: " + prepared.tariffCode(),
                properties.returnUrl(),
                Map.of("payment_order_id", prepared.orderId().toString()));
    }

    private PaymentCheckoutResult result(PreparedCheckout prepared) {
        return new PaymentCheckoutResult(
                prepared.orderId(), prepared.tariffName(), prepared.amount(),
                prepared.currency(), prepared.durationDays(), prepared.status(),
                prepared.confirmationUrl(), prepared.localExpiresAt());
    }
}
