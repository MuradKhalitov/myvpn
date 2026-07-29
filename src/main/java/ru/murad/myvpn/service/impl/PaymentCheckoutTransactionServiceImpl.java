package ru.murad.myvpn.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.murad.myvpn.client.PaymentConfirmationUrl;
import ru.murad.myvpn.dto.CreatedPayment;
import ru.murad.myvpn.dto.CheckoutDestination;
import ru.murad.myvpn.dto.PaymentCheckoutResult;
import ru.murad.myvpn.dto.PreparedCheckout;
import ru.murad.myvpn.exception.OpenPaymentOrderAlreadyExistsException;
import ru.murad.myvpn.exception.OpenPaymentOrderDifferentTariffException;
import ru.murad.myvpn.exception.PaymentNotFoundException;
import ru.murad.myvpn.exception.PaymentOrderValidationException;
import ru.murad.myvpn.exception.PaymentProviderUncertainException;
import ru.murad.myvpn.exception.PaymentStateTransitionException;
import ru.murad.myvpn.exception.TelegramUserNotFoundException;
import ru.murad.myvpn.exception.VpnTariffNotFoundException;
import ru.murad.myvpn.model.PaymentOrder;
import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.model.PaymentStatus;
import ru.murad.myvpn.repository.PaymentOrderRepository;
import ru.murad.myvpn.repository.TelegramUserRepository;
import ru.murad.myvpn.repository.VpnTariffRepository;
import ru.murad.myvpn.service.CreatedPaymentValidator;
import ru.murad.myvpn.service.PaymentCheckoutTransactionService;
import ru.murad.myvpn.service.PaymentOrderCreationService;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class PaymentCheckoutTransactionServiceImpl
        implements PaymentCheckoutTransactionService {

    private final TelegramUserRepository userRepository;
    private final VpnTariffRepository tariffRepository;
    private final PaymentOrderRepository orderRepository;
    private final PaymentOrderCreationService orderCreationService;
    private final CreatedPaymentValidator createdPaymentValidator;

    @Override
    @Transactional
    public PreparedCheckout prepareCheckout(
            long telegramUserId,
            String tariffCode,
            PaymentProviderType provider,
            Duration pendingTtl,
            Instant now
    ) {
        var user = userRepository.findByTelegramId(telegramUserId)
                .orElseThrow(() -> new TelegramUserNotFoundException(telegramUserId));
        var tariff = tariffRepository.findByCodeAndActiveTrue(tariffCode)
                .orElseThrow(() -> new VpnTariffNotFoundException(tariffCode));
        PaymentOrder order = orderRepository.findOpenByUser(user.getId()).orElse(null);
        if (order != null && !order.getTariff().getId().equals(tariff.getId())) {
            throw new OpenPaymentOrderDifferentTariffException();
        }
        if (order != null
                && (order.getStatus() == PaymentStatus.PENDING
                || order.getStatus() == PaymentStatus.CREATING)
                && !order.getExpiresAt().isAfter(now)) {
            order.markExpired(now);
            orderRepository.saveAndFlush(order);
            order = null;
        }
        if (order == null) {
            order = orderCreationService.create(
                    telegramUserId, tariffCode, provider, pendingTtl);
        }
        if (!order.getTariff().getId().equals(tariff.getId())) {
            throw new OpenPaymentOrderDifferentTariffException();
        }
        if (order.getProvider() != provider) {
            throw new OpenPaymentOrderAlreadyExistsException();
        }
        if (order.getStatus() == PaymentStatus.NEW) {
            order.markCreating(now);
            orderRepository.saveAndFlush(order);
        } else if (order.getStatus() != PaymentStatus.CREATING
                && order.getStatus() != PaymentStatus.PENDING) {
            throw new OpenPaymentOrderAlreadyExistsException();
        }
        return prepared(order);
    }

    @Override
    @Transactional
    public PaymentCheckoutResult applyCreatedPayment(
            PreparedCheckout prepared,
            CreatedPayment created,
            Instant now
    ) {
        PaymentOrder order = orderRepository.findByIdForUpdate(prepared.orderId())
                .orElseThrow(PaymentNotFoundException::new);
        validateIdentity(order, prepared);
        createdPaymentValidator.validate(order.getProvider(), created, now);
        try {
            order.markPending(
                    created.providerPaymentId(),
                    created.confirmationUrl().toString(),
                    created.providerCreatedAt(),
                    created.expiresAt(),
                    now);
        } catch (PaymentOrderValidationException
                 | PaymentStateTransitionException invalidExternalData) {
            throw new PaymentProviderUncertainException(
                    "Payment provider result could not be applied safely");
        }
        orderRepository.saveAndFlush(order);
        return result(order);
    }

    @Override
    @Transactional
    public void markPermanentFailure(PreparedCheckout prepared, Instant now) {
        PaymentOrder order = orderRepository.findByIdForUpdate(prepared.orderId())
                .orElseThrow(PaymentNotFoundException::new);
        validateIdentity(order, prepared);
        if (order.getStatus() == PaymentStatus.PENDING) {
            return;
        }
        if (order.getStatus() != PaymentStatus.CREATING) {
            throw new PaymentProviderUncertainException(
                    "Payment order state changed concurrently");
        }
        order.markFailed("PROVIDER_REJECTED", now);
        orderRepository.saveAndFlush(order);
    }

    @Override
    @Transactional
    public void markTelegramInvoiceUncertain(PreparedCheckout prepared, Instant now) {
        PaymentOrder order = orderRepository.findByIdForUpdate(prepared.orderId())
                .orElseThrow(PaymentNotFoundException::new);
        validateIdentity(order, prepared);
        if (order.getStatus() == PaymentStatus.CREATING
                || order.getStatus() == PaymentStatus.PENDING) {
            order.markPaymentManualReviewRequired(
                    "TELEGRAM_INVOICE_UNCERTAIN", now);
            orderRepository.saveAndFlush(order);
        }
    }

    private void validateIdentity(PaymentOrder order, PreparedCheckout prepared) {
        if (!order.getUser().getId().equals(prepared.userId())
                || !order.getTariff().getId().equals(prepared.tariffId())
                || order.getProvider() != prepared.provider()
                || !order.getIdempotenceKey().equals(prepared.idempotenceKey())
                || order.getAmount().compareTo(prepared.amount()) != 0
                || !order.getCurrency().equals(prepared.currency())
                || !order.getTariffCodeSnapshot().equals(prepared.tariffCode())) {
            throw new PaymentProviderUncertainException(
                    "Payment order state changed concurrently");
        }
    }

    private PreparedCheckout prepared(PaymentOrder order) {
        URI url = null;
        if (order.getStatus() == PaymentStatus.PENDING) {
            try {
                url = URI.create(order.getConfirmationUrl());
                validateUrl(order.getProvider(), url);
            } catch (RuntimeException invalid) {
                throw new PaymentProviderUncertainException(
                        "Stored payment confirmation is invalid");
            }
        }
        return new PreparedCheckout(
                order.getId(), order.getUser().getId(), order.getUser().getTelegramId(),
                order.getUser().getChatId(), order.getTariff().getId(),
                order.getProvider(), order.getIdempotenceKey(), order.getAmount(),
                order.getCurrency(), order.getTariffCodeSnapshot(),
                order.getTariffNameSnapshot(), order.getDurationDaysSnapshot(),
                order.getStatus(), url, order.getTelegramInvoicePayload(),
                order.getTelegramInvoiceMessageId(), order.getExpiresAt());
    }

    @Override
    @Transactional
    public PaymentCheckoutResult applyTelegramInvoice(
            PreparedCheckout prepared, int messageId, Instant now
    ) {
        PaymentOrder order = orderRepository.findByIdForUpdate(prepared.orderId())
                .orElseThrow(PaymentNotFoundException::new);
        validateIdentity(order, prepared);
        try {
            order.markTelegramInvoiceSent(messageId, now);
            orderRepository.saveAndFlush(order);
        } catch (PaymentOrderValidationException | PaymentStateTransitionException invalid) {
            throw new PaymentProviderUncertainException(
                    "Telegram invoice result could not be applied safely");
        }
        return result(order);
    }

    private PaymentCheckoutResult result(PaymentOrder order) {
        if (order.getProvider() == PaymentProviderType.TELEGRAM_YOOKASSA) {
            Integer messageId = order.getTelegramInvoiceMessageId();
            if (messageId == null) {
                throw new PaymentProviderUncertainException(
                        "Telegram invoice result is incomplete");
            }
            return new PaymentCheckoutResult(
                    order.getId(), order.getTariffNameSnapshot(), order.getAmount(),
                    order.getCurrency(), order.getDurationDaysSnapshot(),
                    order.getStatus(), new CheckoutDestination.TelegramInvoiceSent(messageId),
                    order.getExpiresAt());
        }
        URI url;
        try {
            url = URI.create(order.getConfirmationUrl());
            validateUrl(order.getProvider(), url);
        } catch (RuntimeException invalid) {
            throw new PaymentProviderUncertainException(
                    "Stored payment confirmation is invalid");
        }
        return new PaymentCheckoutResult(
                order.getId(), order.getTariffNameSnapshot(), order.getAmount(),
                order.getCurrency(), order.getDurationDaysSnapshot(),
                order.getStatus(), new CheckoutDestination.RedirectUrl(url),
                order.getExpiresAt());
    }

    private void validateUrl(PaymentProviderType provider, URI url) {
        if (provider == PaymentProviderType.FAKE) {
            PaymentConfirmationUrl.fake(url);
            return;
        }
        if (provider == PaymentProviderType.YOOKASSA
                && "https".equalsIgnoreCase(url.getScheme())
                && url.getHost() != null && !url.getHost().isBlank()) {
            return;
        }
        throw new PaymentProviderUncertainException(
                "Payment confirmation validator is not configured");
    }
}
