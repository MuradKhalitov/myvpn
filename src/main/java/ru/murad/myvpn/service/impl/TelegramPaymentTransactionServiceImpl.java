package ru.murad.myvpn.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.murad.myvpn.dto.TelegramPreCheckoutCommand;
import ru.murad.myvpn.dto.TelegramSuccessfulPaymentCommand;
import ru.murad.myvpn.exception.PaymentProviderUncertainException;
import ru.murad.myvpn.model.PaymentOrder;
import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.model.PaymentStatus;
import ru.murad.myvpn.repository.PaymentOrderRepository;
import ru.murad.myvpn.service.TelegramPaymentTransactionService;
import ru.murad.myvpn.config.ConditionalOnTelegramYooKassa;

import java.math.RoundingMode;
import java.time.Clock;

@Service
@RequiredArgsConstructor
@ConditionalOnTelegramYooKassa
public class TelegramPaymentTransactionServiceImpl
        implements TelegramPaymentTransactionService {

    private final PaymentOrderRepository orders;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public boolean validatePreCheckout(TelegramPreCheckoutCommand command) {
        if (!validBasic(command.payload(), command.currency(), command.totalAmount())) {
            return false;
        }
        return orders.findByTelegramInvoicePayload(command.payload())
                .filter(order -> order.getProvider() == PaymentProviderType.TELEGRAM_YOOKASSA)
                .filter(order -> order.getUser().getTelegramId() == command.telegramUserId())
                .filter(order -> order.getStatus() == PaymentStatus.PENDING)
                .filter(order -> order.getExpiresAt().isAfter(clock.instant()))
                .filter(order -> minor(order) == command.totalAmount())
                .isPresent();
    }

    @Override
    @Transactional
    public void applySuccessfulPayment(TelegramSuccessfulPaymentCommand command) {
        if (!validBasic(command.payload(), command.currency(), command.totalAmount())
                || blank(command.telegramPaymentChargeId())
                || blank(command.providerPaymentChargeId())) {
            throw rejected();
        }
        PaymentOrder order = orders.findByTelegramInvoicePayloadForUpdate(command.payload())
                .orElseThrow(TelegramPaymentTransactionServiceImpl::rejected);
        if (order.getProvider() != PaymentProviderType.TELEGRAM_YOOKASSA
                || order.getUser().getTelegramId() != command.telegramUserId()
                || minor(order) != command.totalAmount()
                || !"RUB".equals(order.getCurrency())) {
            throw rejected();
        }
        if (order.getStatus() == PaymentStatus.SUCCEEDED) {
            if (command.telegramPaymentChargeId().equals(
                    order.getTelegramPaymentChargeId())
                    && command.providerPaymentChargeId().equals(
                    order.getProviderPaymentChargeId())) {
                return;
            }
            throw rejected();
        }
        if ((order.getStatus() != PaymentStatus.PENDING
                && order.getStatus() != PaymentStatus.MANUAL_REVIEW_REQUIRED)
                || !order.getExpiresAt().isAfter(command.receivedAt())) {
            throw rejected();
        }
        assertChargeUnused(command.telegramPaymentChargeId(),
                command.providerPaymentChargeId(), order);
        order.recordSuccessfulTelegramPayment(
                command.telegramPaymentChargeId(),
                command.providerPaymentChargeId(),
                command.receivedAt(), command.receivedAt());
        try {
            orders.saveAndFlush(order);
        } catch (org.springframework.dao.DataIntegrityViolationException collision) {
            throw rejected();
        }
    }

    private void assertChargeUnused(
            String telegramCharge, String providerCharge, PaymentOrder order
    ) {
        boolean telegramUsed = orders.findByTelegramPaymentChargeId(telegramCharge)
                .filter(found -> !found.getId().equals(order.getId())).isPresent();
        boolean providerUsed = orders.findByProviderPaymentChargeId(providerCharge)
                .filter(found -> !found.getId().equals(order.getId())).isPresent();
        if (telegramUsed || providerUsed) throw rejected();
    }

    private boolean validBasic(String payload, String currency, long amount) {
        return payload != null && payload.matches("[A-Za-z0-9_-]{43}")
                && "RUB".equals(currency) && amount > 0;
    }

    private long minor(PaymentOrder order) {
        try {
            return order.getAmount().setScale(2, RoundingMode.UNNECESSARY)
                    .movePointRight(2).longValueExact();
        } catch (ArithmeticException exception) {
            throw rejected();
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static PaymentProviderUncertainException rejected() {
        return new PaymentProviderUncertainException(
                "Telegram successful payment did not match the local order");
    }
}
