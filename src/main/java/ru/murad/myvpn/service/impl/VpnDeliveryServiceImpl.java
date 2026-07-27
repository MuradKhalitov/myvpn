package ru.murad.myvpn.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.murad.myvpn.config.VpnDeliveryProperties;
import ru.murad.myvpn.dto.VpnDeliveryMessage;
import ru.murad.myvpn.exception.TelegramDeliveryPermanentException;
import ru.murad.myvpn.exception.TelegramDeliveryTransientException;
import ru.murad.myvpn.exception.VpnDeliveryMessageTooLongException;
import ru.murad.myvpn.exception.VpnDeliveryRelationshipMismatchException;
import ru.murad.myvpn.exception.SafeExceptionLogFormatter;
import ru.murad.myvpn.model.*;
import ru.murad.myvpn.repository.SubscriptionRepository;
import ru.murad.myvpn.repository.VpnAccessRepository;
import ru.murad.myvpn.repository.PaymentOrderRepository;
import ru.murad.myvpn.service.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * Delivery is at-least-once: a process crash after Telegram accepts a message and before TX2
 * persists DELIVERED can lead to a lease reclaim and a duplicate message.
 */
@Service @Slf4j @RequiredArgsConstructor @ConditionalOnProperty(name = "vpn.delivery.enabled", havingValue = "true")
public class VpnDeliveryServiceImpl implements VpnDeliveryService {
    private static final int TELEGRAM_TEXT_LIMIT = 4096;
    private final VpnDeliveryTransactionService transactions; private final VpnConfigurationDeliveryGateway gateway;
    private final SubscriptionRepository subscriptions; private final VpnAccessRepository accesses; private final PaymentOrderRepository orders;
    private final VpnDeliveryProperties properties; private final Clock clock;
    @Override public VpnDeliveryWorkerResult processPendingDeliveries(int limit) {
        if (limit <= 0 || limit > 100) throw new IllegalArgumentException("Delivery batch limit is invalid");
        Instant now = clock.instant();
        int exhausted = transactions.markExhaustedDeliveries(now, limit);
        int claimed = 0, delivered = 0, retried = 0, manual = 0, skipped = 0, infrastructure = 0;
        for (ClaimedVpnDelivery claim : transactions.claim(now, limit)) {
            claimed++;
            try {
                DeliveryOutcome outcome = processSingleClaim(claim);
                switch (outcome) {
                    case DELIVERED -> delivered++;
                    case RETRY -> retried++;
                    case MANUAL -> manual++;
                    case SKIPPED -> skipped++;
                }
            } catch (RuntimeException exception) {
                infrastructure++;
                log.error("VPN delivery infrastructure failure; claim remains recoverable\n{}",
                        SafeExceptionLogFormatter.format(exception));
            }
        }
        return new VpnDeliveryWorkerResult(claimed, exhausted, delivered, retried, manual, skipped, infrastructure);
    }
    private DeliveryOutcome processSingleClaim(ClaimedVpnDelivery claim) {
            assertNoTransaction();
            Optional<VpnDeliveryMessage> message;
            try { message = loadCurrentMessage(claim); } catch (VpnDeliveryMessageTooLongException tooLong) {
                return transactions.manualReview(claim, VpnDeliveryFailureCode.TELEGRAM_MESSAGE_TOO_LONG, clock.instant()) ? DeliveryOutcome.MANUAL : DeliveryOutcome.SKIPPED;
            } catch (VpnDeliveryRelationshipMismatchException mismatch) {
                return transactions.manualReview(claim, VpnDeliveryFailureCode.RELATIONSHIP_MISMATCH, clock.instant()) ? DeliveryOutcome.MANUAL : DeliveryOutcome.SKIPPED;
            }
            if (message.isEmpty()) return transactions.manualReview(claim, VpnDeliveryFailureCode.SNAPSHOT_INVALID, clock.instant()) ? DeliveryOutcome.MANUAL : DeliveryOutcome.SKIPPED;
            Long messageId;
            try {
                messageId = gateway.deliver(message.get());
            } catch (TelegramDeliveryTransientException transientFailure) {
                Duration requested = transientFailure.retryAfter();
                Duration calculated = retryDelay(claim);
                Duration boundedRequested = requested != null && !requested.isNegative() && !requested.isZero()
                        ? (requested.compareTo(properties.retryMaxDelay()) > 0 ? properties.retryMaxDelay() : requested) : Duration.ZERO;
                Duration delay = boundedRequested.compareTo(calculated) > 0 ? boundedRequested : calculated;
                return transactions.retry(claim, transientFailure.safeFailureCode(),
                        clock.instant(), clock.instant().plus(delay)) ? DeliveryOutcome.RETRY : DeliveryOutcome.SKIPPED;
            } catch (TelegramDeliveryPermanentException permanentFailure) {
                return transactions.manualReview(claim, permanentFailure.safeFailureCode(), clock.instant()) ? DeliveryOutcome.MANUAL : DeliveryOutcome.SKIPPED;
            } catch (RuntimeException unexpected) {
                return transactions.retry(claim, VpnDeliveryFailureCode.TELEGRAM_GATEWAY_UNEXPECTED, clock.instant(), clock.instant().plus(retryDelay(claim))) ? DeliveryOutcome.RETRY : DeliveryOutcome.SKIPPED;
            }
            return transactions.delivered(claim, messageId, clock.instant()) ? DeliveryOutcome.DELIVERED : DeliveryOutcome.SKIPPED;
    }
    @Override public Optional<VpnDeliveryMessage> loadCurrentMessage(ClaimedVpnDelivery claim) {
        Subscription sub = subscriptions.findByIdForDelivery(claim.subscriptionId()).orElse(null);
        VpnAccess access = accesses.findByIdForDelivery(claim.vpnAccessId()).orElse(null);
        PaymentOrder order = orders.findByIdForDelivery(claim.sourcePaymentOrderId()).orElse(null);
        if (!relationshipGraphMatches(claim, sub, access, order)) throw new VpnDeliveryRelationshipMismatchException();
        if (sub == null || access == null || sub.getStatus() != SubscriptionStatus.ACTIVE || access.getStatus() != VpnAccessStatus.ACTIVE
                || !sub.getUser().getId().equals(claim.userId()) || version(sub.getVersion()) != claim.subscriptionVersion()
                || !sub.getExpiresAt().equals(claim.subscriptionExpiresAt())
                || version(access.getVersion()) != claim.vpnAccessVersion() || !access.getProviderName().equals(claim.vpnProviderName())
                || !access.getExternalAccessId().equals(claim.vpnExternalAccessId())
                || !fingerprint(access.getConfigurationData()).equals(claim.configurationFingerprint())
                || !sub.getExpiresAt().isAfter(clock.instant()) || access.getConfigurationData() == null || access.getConfigurationData().isBlank()) return Optional.empty();
        String date = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(sub.getExpiresAt());
        if (claim.type() == VpnDeliveryType.ACTIVATION_PROVISION) {
            String text = "VPN активирован.\n\nТариф: " + claim.tariffName()
                    + "\nДействует до: " + date + "\n\nКонфигурация:\n"
                    + access.getConfigurationData()
                    + "\n\nНикому не пересылайте эту конфигурацию.";
            if (text.length() > TELEGRAM_TEXT_LIMIT) throw new VpnDeliveryMessageTooLongException();
            return Optional.of(new VpnDeliveryMessage(claim.telegramId(), text, sub.getExpiresAt()));
        }
        if (claim.type() == VpnDeliveryType.ACTIVATION_EXTEND) {
            String text = "VPN продлён.\n\nНовый срок действия: " + date
                    + "\n\nТекущая конфигурация:\n" + access.getConfigurationData();
            if (text.length() > TELEGRAM_TEXT_LIMIT) throw new VpnDeliveryMessageTooLongException();
            return Optional.of(new VpnDeliveryMessage(claim.telegramId(), text, sub.getExpiresAt()));
        }
        String text = claim.type() == VpnDeliveryType.ACTIVATION_PROVISION
                ? "VPN активирован.\n\nТариф: " + claim.tariffName() + "\nДействует до: " + date + "\n\nКонфигурация:\n" + access.getConfigurationData() + "\n\nНикому не пересылайте эту конфигурацию."
                : "VPN продлён.\n\nНовый срок действия: " + date + "\n\nТекущая конфигурация:\n" + access.getConfigurationData();
        if (text.length() > TELEGRAM_TEXT_LIMIT) throw new VpnDeliveryMessageTooLongException();
        return Optional.of(new VpnDeliveryMessage(claim.telegramId(), text, sub.getExpiresAt()));
    }
    private Duration retryDelay(ClaimedVpnDelivery claim) {
        long multiplier = 1L << Math.min(20, Math.max(0, claim.generation() - 1));
        Duration calculated; try { calculated = properties.retryInitialDelay().multipliedBy(multiplier); } catch (ArithmeticException overflow) { calculated = properties.retryMaxDelay(); }
        return calculated.compareTo(properties.retryMaxDelay()) > 0 ? properties.retryMaxDelay() : calculated;
    }
    private void assertNoTransaction() { if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Telegram delivery must be outside transaction"); }
    private long version(Long value) { return value == null ? 0L : value; }
    private boolean relationshipGraphMatches(ClaimedVpnDelivery claim, Subscription subscription,
            VpnAccess access, PaymentOrder order) {
        return subscription != null && access != null && order != null
                && same(claim.userId(), subscription.getUser().getId())
                && same(claim.subscriptionId(), access.getSubscription().getId())
                && same(claim.userId(), order.getUser().getId())
                && order.getSubscription() != null && same(claim.subscriptionId(), order.getSubscription().getId());
    }
    private boolean same(java.util.UUID left, java.util.UUID right) { return left != null && left.equals(right); }
    private String fingerprint(String configuration) { if (configuration == null || configuration.isBlank()) return ""; try { byte[] bytes = MessageDigest.getInstance("SHA-256").digest(configuration.getBytes(StandardCharsets.UTF_8)); StringBuilder output = new StringBuilder(64); for (byte b : bytes) output.append(String.format("%02x", b)); return output.toString(); } catch (Exception impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); } }
    private enum DeliveryOutcome { DELIVERED, RETRY, MANUAL, SKIPPED }
}
