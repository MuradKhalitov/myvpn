package ru.murad.myvpn.service.impl;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.murad.myvpn.dto.*;
import ru.murad.myvpn.exception.*;
import ru.murad.myvpn.model.*;
import ru.murad.myvpn.repository.PaymentOrderRepository;
import ru.murad.myvpn.repository.TelegramUserRepository;
import ru.murad.myvpn.service.PaymentVerificationTransactionService;
import java.time.*;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PaymentVerificationTransactionServiceImpl implements PaymentVerificationTransactionService {
    private final TelegramUserRepository users;
    private final PaymentOrderRepository orders;
    private final EntityManager entityManager;

    @Override @Transactional
    public PaymentVerificationPreparation prepare(long telegramId, Instant now, Duration interval) {
        var user = users.findByTelegramId(telegramId).orElseThrow(() -> new TelegramUserNotFoundException(telegramId));
        List<PaymentOrder> all = orders.findAllByUserOrderByCreatedAtDesc(user.getId());
        List<PaymentOrder> relevant = all.stream().filter(this::blocking).toList();
        if (relevant.size() > 1) return new PaymentVerificationPreparation(null,
                new PaymentVerificationResult(PaymentVerificationOutcome.AMBIGUOUS_PAYMENT_STATE, null, null, null, null));
        PaymentOrder order = relevant.stream()
                .sorted(java.util.Comparator.comparingInt((PaymentOrder p) -> rank(p.getStatus()))
                        .thenComparing(PaymentOrder::getCreatedAt, java.util.Comparator.reverseOrder()))
                .findFirst().orElse(null);
        if (order == null) {
            order = all.stream().filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED
                    || p.getStatus() == PaymentStatus.CANCELED
                    || p.getStatus() == PaymentStatus.FAILED || p.getStatus() == PaymentStatus.EXPIRED)
                    .max(java.util.Comparator.comparing(PaymentOrder::getCreatedAt)
                            .thenComparing(PaymentOrder::getId)).orElse(null);
        }
        if (order == null) return new PaymentVerificationPreparation(null, new PaymentVerificationResult(PaymentVerificationOutcome.NOT_FOUND, null, null, null, null));
        entityManager.clear();
        order = orders.findByIdForUpdate(order.getId()).orElseThrow(PaymentNotFoundException::new);
        var immediate = immediate(order, now, interval);
        if (immediate != null) return new PaymentVerificationPreparation(null, immediate);
        if (!order.reserveVerification(now, interval))
            return new PaymentVerificationPreparation(null, result(order, PaymentVerificationOutcome.TOO_EARLY));
        orders.saveAndFlush(order);
        return new PaymentVerificationPreparation(snapshot(order), null);
    }

    @Override @Transactional
    public PaymentVerificationResult apply(PreparedPaymentVerification expected, ProviderPayment actual, Instant now) {
        PaymentOrder order = orders.findByIdForUpdate(expected.paymentOrderId()).orElseThrow(PaymentNotFoundException::new);
        if (!snapshotMatches(order, expected) || actual == null
                || !order.getProviderPaymentId().equals(actual.providerPaymentId()))
            return result(order, PaymentVerificationOutcome.PROVIDER_RESULT_UNCERTAIN);
        if (order.getStatus() == PaymentStatus.SUCCEEDED) return result(order, PaymentVerificationOutcome.ALREADY_SUCCEEDED);
        if (order.getStatus() == PaymentStatus.CANCELED) return result(order, PaymentVerificationOutcome.ALREADY_CANCELED);
        if (order.getStatus() == PaymentStatus.MANUAL_REVIEW_REQUIRED || order.getStatus() == PaymentStatus.FAILED || order.getStatus() == PaymentStatus.EXPIRED)
            return result(order, PaymentVerificationOutcome.MANUAL_REVIEW_REQUIRED);
        if (actual.status() == ProviderPaymentStatus.SUCCEEDED) {
            order.markSucceeded(actual.paidAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS), now);
            orders.saveAndFlush(order); return result(order, PaymentVerificationOutcome.SUCCEEDED);
        }
        if (actual.status() == ProviderPaymentStatus.CANCELED) {
            order.markCanceled(now); orders.saveAndFlush(order); return result(order, PaymentVerificationOutcome.CANCELED);
        }
        if (actual.status() == ProviderPaymentStatus.UNKNOWN)
            return result(order, PaymentVerificationOutcome.PROVIDER_RESULT_UNCERTAIN);
        return result(order, PaymentVerificationOutcome.STILL_PENDING);
    }

    @Override @Transactional
    public PaymentVerificationResult manualReview(PreparedPaymentVerification expected, String code, Instant now) {
        PaymentOrder order = orders.findByIdForUpdate(expected.paymentOrderId()).orElseThrow(PaymentNotFoundException::new);
        if (order.getStatus() == PaymentStatus.MANUAL_REVIEW_REQUIRED) return result(order, PaymentVerificationOutcome.MANUAL_REVIEW_REQUIRED);
        if (order.getStatus() == PaymentStatus.CREATING || order.getStatus() == PaymentStatus.PENDING) {
            order.markPaymentManualReviewRequired(code, now); orders.saveAndFlush(order);
            return result(order, PaymentVerificationOutcome.MANUAL_REVIEW_REQUIRED);
        }
        return result(order, PaymentVerificationOutcome.PROVIDER_RESULT_UNCERTAIN);
    }

    private boolean blocking(PaymentOrder p) { return p.getStatus() == PaymentStatus.PENDING || p.getStatus() == PaymentStatus.CREATING || p.getStatus() == PaymentStatus.MANUAL_REVIEW_REQUIRED; }
    private int rank(PaymentStatus status) { return switch (status) { case PENDING -> 1; case CREATING -> 2; case MANUAL_REVIEW_REQUIRED -> 3; case SUCCEEDED -> 4; case CANCELED -> 5; default -> 6; }; }
    private PaymentVerificationResult immediate(PaymentOrder p, Instant now, Duration interval) {
        if (p.getStatus() == PaymentStatus.SUCCEEDED) return result(p, PaymentVerificationOutcome.ALREADY_SUCCEEDED);
        if (p.getStatus() == PaymentStatus.CANCELED) return result(p, PaymentVerificationOutcome.ALREADY_CANCELED);
        if (p.getStatus() == PaymentStatus.MANUAL_REVIEW_REQUIRED) return result(p, PaymentVerificationOutcome.MANUAL_REVIEW_REQUIRED);
        if (p.getStatus() == PaymentStatus.FAILED || p.getStatus() == PaymentStatus.EXPIRED) return result(p, PaymentVerificationOutcome.TERMINAL);
        if (p.getProviderPaymentId() == null) return result(p, PaymentVerificationOutcome.CHECKOUT_INCOMPLETE);
        return null;
    }
    private PreparedPaymentVerification snapshot(PaymentOrder p) { return new PreparedPaymentVerification(p.getId(), p.getUser().getId(), p.getProvider(), p.getProviderPaymentId(), p.getAmount(), p.getCurrency(), p.getTariff().getId(), p.getTariffCodeSnapshot(), p.getTariffNameSnapshot(), p.getDurationDaysSnapshot(), p.getStatus(), p.getIdempotenceKey()); }
    private PaymentVerificationResult result(PaymentOrder p, PaymentVerificationOutcome o) { return new PaymentVerificationResult(o, p.getStatus(), p.getActivationStatus(), p.getPaidAt(), p.getNextVerificationAt()); }
    private boolean snapshotMatches(PaymentOrder o, PreparedPaymentVerification e) {
        return o.getId().equals(e.paymentOrderId()) && o.getUser().getId().equals(e.userId())
                && o.getProvider() == e.provider() && java.util.Objects.equals(o.getProviderPaymentId(), e.providerPaymentId())
                && o.getAmount().compareTo(e.amount()) == 0 && o.getCurrency().equals(e.currency())
                && o.getTariff().getId().equals(e.tariffId())
                && o.getTariffCodeSnapshot().equals(e.tariffCodeSnapshot())
                && o.getTariffNameSnapshot().equals(e.tariffNameSnapshot())
                && o.getDurationDaysSnapshot() == e.durationDaysSnapshot()
                && o.getIdempotenceKey().equals(e.idempotenceKey());
    }
}
