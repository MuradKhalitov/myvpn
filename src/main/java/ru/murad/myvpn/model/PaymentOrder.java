package ru.murad.myvpn.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.proxy.HibernateProxy;
import ru.murad.myvpn.exception.PaymentOrderValidationException;
import ru.murad.myvpn.exception.PaymentStateTransitionException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.security.SecureRandom;
import java.util.Base64;

@Entity
@Table(name = "payment_orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentOrder {

    private static final String MVP_CURRENCY = "RUB";
    private static final int CONFIRMATION_URL_MAX_LENGTH = 2048;
    private static final Duration MAX_PENDING_TTL = Duration.ofHours(24);
    private static final int FAILURE_CODE_MAX_LENGTH = 64;
    private static final Pattern FAILURE_CODE_PATTERN =
            Pattern.compile("[A-Z0-9_]{1,64}");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private TelegramUser user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tariff_id", nullable = false)
    private VpnTariff tariff;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id")
    private Subscription subscription;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentProviderType provider;

    @Column(name = "provider_payment_id", length = 128)
    private String providerPaymentId;

    @Column(name = "idempotence_key", nullable = false, unique = true)
    private UUID idempotenceKey;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "activation_status", nullable = false, length = 32)
    private PaymentActivationStatus activationStatus;

    @Column(name = "confirmation_url", columnDefinition = "text")
    private String confirmationUrl;

    @Column(name = "telegram_invoice_payload", length = 64, unique = true)
    private String telegramInvoicePayload;

    @Column(name = "telegram_invoice_message_id")
    private Integer telegramInvoiceMessageId;

    @Column(name = "telegram_payment_charge_id", length = 256, unique = true)
    private String telegramPaymentChargeId;

    @Column(name = "provider_payment_charge_id", length = 256, unique = true)
    private String providerPaymentChargeId;

    @Column(name = "tariff_code_snapshot", nullable = false, length = 64)
    private String tariffCodeSnapshot;

    @Column(name = "tariff_name_snapshot", nullable = false, length = 128)
    private String tariffNameSnapshot;

    @Column(name = "duration_days_snapshot", nullable = false)
    private Integer durationDaysSnapshot;

    @Column(name = "provider_created_at")
    private Instant providerCreatedAt;

    @Column(name = "provider_expires_at")
    private Instant providerExpiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "next_verification_at")
    private Instant nextVerificationAt;

    @Column(name = "verification_attempts", nullable = false)
    private Integer verificationAttempts;

    @Column(name = "activation_target_expires_at")
    private Instant activationTargetExpiresAt;

    @Column(name = "activation_lease_until")
    private Instant activationLeaseUntil;

    @Column(name = "activation_claim_token")
    private UUID activationClaimToken;

    @Column(name = "activation_generation", nullable = false)
    private long activationGeneration;

    @Column(name = "activation_attempts", nullable = false)
    private Integer activationAttempts;

    @Column(name = "next_activation_at")
    private Instant nextActivationAt;

    @Column(name = "safe_failure_code", length = 64)
    private String safeFailureCode;

    @Column(name = "activation_completed_at")
    private Instant activationCompletedAt;

    @Version
    @Column(nullable = false)
    private long version;

    public static PaymentOrder create(
            TelegramUser user,
            VpnTariff tariff,
            PaymentProviderType provider,
            Instant now,
            Duration pendingTtl
    ) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(tariff, "tariff");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(now, "now");
        validatePendingTtl(pendingTtl);

        BigDecimal amount = rubAmount(tariff.getPrice());
        requireText(tariff.getCode(), "Tariff code");
        requireText(tariff.getName(), "Tariff name");
        if (tariff.getCode().length() > 64) {
            throw new PaymentOrderValidationException("Tariff code is too long");
        }
        if (tariff.getName().length() > 128) {
            throw new PaymentOrderValidationException("Tariff name is too long");
        }
        if (!MVP_CURRENCY.equals(tariff.getCurrency())) {
            throw new PaymentOrderValidationException("Currency must be RUB");
        }
        if (tariff.getDurationDays() <= 0) {
            throw new PaymentOrderValidationException("Duration must be positive");
        }

        Instant expiresAt;
        try {
            expiresAt = now.plus(pendingTtl);
        } catch (DateTimeException | ArithmeticException exception) {
            throw new PaymentOrderValidationException("Pending TTL cannot be applied");
        }

        PaymentOrder order = new PaymentOrder();
        order.id = UUID.randomUUID();
        order.user = user;
        order.tariff = tariff;
        order.provider = provider;
        order.idempotenceKey = UUID.randomUUID();
        order.amount = amount;
        order.currency = MVP_CURRENCY;
        order.status = PaymentStatus.NEW;
        order.activationStatus = PaymentActivationStatus.NOT_READY;
        order.tariffCodeSnapshot = tariff.getCode();
        order.tariffNameSnapshot = tariff.getName();
        order.durationDaysSnapshot = tariff.getDurationDays();
        order.createdAt = now;
        order.updatedAt = now;
        order.expiresAt = expiresAt;
        order.verificationAttempts = 0;
        order.activationAttempts = 0;
        if (provider == PaymentProviderType.TELEGRAM_YOOKASSA) {
            byte[] payloadBytes = new byte[32];
            SECURE_RANDOM.nextBytes(payloadBytes);
            order.telegramInvoicePayload = Base64.getUrlEncoder()
                    .withoutPadding().encodeToString(payloadBytes);
        }
        return order;
    }

    public void markTelegramInvoiceSent(int messageId, Instant now) {
        Objects.requireNonNull(now, "now");
        if (provider != PaymentProviderType.TELEGRAM_YOOKASSA
                || telegramInvoicePayload == null) {
            throw new PaymentStateTransitionException("Telegram invoice is not allowed");
        }
        if (messageId <= 0) {
            throw new PaymentOrderValidationException("Telegram invoice message id must be positive");
        }
        if (status == PaymentStatus.PENDING) {
            if (Objects.equals(telegramInvoiceMessageId, messageId)) return;
            throw new PaymentStateTransitionException("Telegram invoice cannot be replaced");
        }
        transitionPayment(PaymentStatus.PENDING, now, PaymentStatus.CREATING);
        telegramInvoiceMessageId = messageId;
        providerPaymentId = telegramInvoicePayload;
        providerCreatedAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    public void recordSuccessfulTelegramPayment(
            String telegramChargeId, String providerChargeId,
            Instant paidAt, Instant now
    ) {
        requireText(telegramChargeId, "Telegram payment charge id");
        requireText(providerChargeId, "Provider payment charge id");
        if (telegramChargeId.length() > 256 || providerChargeId.length() > 256) {
            throw new PaymentOrderValidationException("Payment charge id is too long");
        }
        if (status == PaymentStatus.SUCCEEDED) {
            if (telegramChargeId.equals(telegramPaymentChargeId)
                    && providerChargeId.equals(providerPaymentChargeId)) return;
            throw new PaymentStateTransitionException("Payment charge ids cannot change");
        }
        this.telegramPaymentChargeId = telegramChargeId;
        this.providerPaymentChargeId = providerChargeId;
        markSucceeded(paidAt, now);
    }

    public void markCreating(Instant now) {
        transitionPayment(PaymentStatus.CREATING, now, PaymentStatus.NEW);
    }

    public void markPending(
            String providerPaymentId,
            String confirmationUrl,
            Instant providerCreatedAt,
            Instant providerExpiresAt,
            Instant now
    ) {
        requireText(providerPaymentId, "Provider payment id");
        requireText(confirmationUrl, "Confirmation URL");
        Objects.requireNonNull(providerCreatedAt, "providerCreatedAt");
        Objects.requireNonNull(now, "now");
        if (providerPaymentId.length() > 128) {
            throw new PaymentOrderValidationException("Provider payment id is too long");
        }
        if (confirmationUrl.length() > CONFIRMATION_URL_MAX_LENGTH) {
            throw new PaymentOrderValidationException("Confirmation URL is too long");
        }
        if (providerExpiresAt != null && !providerExpiresAt.isAfter(now)) {
            throw new PaymentOrderValidationException(
                    "Payment expiry must be in the future");
        }
        if (providerExpiresAt != null
                && !providerExpiresAt.isAfter(providerCreatedAt)) {
            throw new PaymentOrderValidationException(
                    "Payment expiry must be after provider creation");
        }
        Instant normalizedProviderCreatedAt = providerCreatedAt
                .truncatedTo(ChronoUnit.MICROS);
        Instant normalizedProviderExpiresAt = providerExpiresAt == null
                ? null : providerExpiresAt.truncatedTo(ChronoUnit.MICROS);
        if (status == PaymentStatus.PENDING) {
            if (providerPaymentId.equals(this.providerPaymentId)
                    && confirmationUrl.equals(this.confirmationUrl)
                    && normalizedProviderCreatedAt.equals(this.providerCreatedAt)
                    && Objects.equals(normalizedProviderExpiresAt,
                    this.providerExpiresAt)) {
                return;
            }
            throw new PaymentStateTransitionException(
                    "External payment details cannot be replaced");
        }
        transitionPayment(PaymentStatus.PENDING, now, PaymentStatus.CREATING);
        this.providerPaymentId = providerPaymentId;
        this.confirmationUrl = confirmationUrl;
        this.providerCreatedAt = normalizedProviderCreatedAt;
        this.providerExpiresAt = normalizedProviderExpiresAt;
    }

    public void markSucceeded(Instant confirmedPaidAt, Instant now) {
        Objects.requireNonNull(confirmedPaidAt, "confirmedPaidAt");
        Objects.requireNonNull(now, "now");
        if (status == PaymentStatus.SUCCEEDED) {
            if (confirmedPaidAt.equals(paidAt)) {
                return;
            }
            throw new PaymentStateTransitionException(
                    "A succeeded payment cannot change paidAt");
        }
        requirePaymentState(PaymentStatus.CREATING, PaymentStatus.PENDING);
        if (activationStatus != PaymentActivationStatus.NOT_READY) {
            throw new PaymentStateTransitionException(
                    "Payment activation is not ready for confirmation");
        }
        status = PaymentStatus.SUCCEEDED;
        activationStatus = PaymentActivationStatus.PENDING;
        paidAt = confirmedPaidAt;
        updatedAt = now;
    }

    public void markCanceled(Instant now) {
        transitionPayment(PaymentStatus.CANCELED, now,
                PaymentStatus.CREATING, PaymentStatus.PENDING);
    }

    public void markExpired(Instant now) {
        transitionPayment(PaymentStatus.EXPIRED, now,
                PaymentStatus.NEW, PaymentStatus.PENDING);
    }

    public void markFailed(String failureCode, Instant now) {
        String validatedCode = validateFailureCode(failureCode);
        transitionPayment(PaymentStatus.FAILED, now,
                PaymentStatus.NEW, PaymentStatus.CREATING, PaymentStatus.PENDING,
                PaymentStatus.MANUAL_REVIEW_REQUIRED);
        safeFailureCode = validatedCode;
    }

    public void markPaymentManualReviewRequired(String failureCode, Instant now) {
        String validatedCode = validateFailureCode(failureCode);
        transitionPayment(PaymentStatus.MANUAL_REVIEW_REQUIRED, now,
                PaymentStatus.CREATING, PaymentStatus.PENDING);
        safeFailureCode = validatedCode;
    }

    public boolean reserveVerification(Instant now, Duration interval) {
        if (now == null) {
            throw new PaymentOrderValidationException("Verification time must not be null");
        }
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new PaymentOrderValidationException("Verification interval must be positive");
        }
        if (nextVerificationAt != null && nextVerificationAt.isAfter(now)) {
            return false;
        }
        int newVerificationAttempts;
        Instant newNextVerificationAt;
        try {
            newVerificationAttempts = Math.incrementExact(verificationAttempts);
        } catch (ArithmeticException ex) {
            throw new PaymentOrderValidationException("Verification attempts limit reached");
        }
        try {
            newNextVerificationAt = now.plus(interval);
        } catch (DateTimeException ex) {
            throw new PaymentOrderValidationException("Verification interval cannot be applied");
        } catch (ArithmeticException ex) {
            throw new PaymentOrderValidationException("Verification interval cannot be applied");
        }
        verificationAttempts = newVerificationAttempts;
        nextVerificationAt = newNextVerificationAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        updatedAt = now.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return true;
    }

    public void scheduleVerificationRetry(Instant now, Duration delay) {
        Objects.requireNonNull(now, "now");
        if (delay == null || delay.isNegative()) {
            throw new PaymentOrderValidationException("Verification retry delay is invalid");
        }
        if (status != PaymentStatus.PENDING && status != PaymentStatus.CREATING) {
            throw new PaymentStateTransitionException("Verification retry is not allowed");
        }
        nextVerificationAt = now.plus(delay).truncatedTo(ChronoUnit.MICROS);
        updatedAt = now.truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Claims activation and returns the new fencing generation. A worker must
     * retain both the supplied token and returned generation for every fenced
     * operation.
     */
    public long claimActivation(
            UUID claimToken,
            Instant leaseUntil,
            Instant now,
            int maxAttempts
    ) {
        Objects.requireNonNull(claimToken, "claimToken");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        Objects.requireNonNull(now, "now");
        requireActivationState(
                PaymentActivationStatus.PENDING,
                PaymentActivationStatus.RETRY_REQUIRED,
                PaymentActivationStatus.RECONCILIATION_REQUIRED);
        if (!leaseUntil.isAfter(now)) {
            throw new PaymentOrderValidationException(
                    "Activation lease must end in the future");
        }
        if (maxAttempts <= 0 || activationAttempts >= maxAttempts) {
            throw new PaymentStateTransitionException(
                    "Activation attempt limit reached");
        }
        long newGeneration = nextActivationGeneration();
        activationStatus = PaymentActivationStatus.PROCESSING;
        activationCompletedAt = null;
        activationClaimToken = claimToken;
        activationGeneration = newGeneration;
        activationLeaseUntil = leaseUntil;
        activationAttempts++;
        updatedAt = now;
        return newGeneration;
    }

    /**
     * Reclaims an expired activation lease and returns a new fencing
     * generation. Callers must hold a pessimistic write lock and retain both
     * the supplied token and returned generation.
     */
    public long reclaimExpiredActivation(
            Instant now,
            Duration leaseDuration,
            UUID newClaimToken,
            int maxAttempts
    ) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(newClaimToken, "newClaimToken");
        if (activationStatus != PaymentActivationStatus.PROCESSING) {
            throw new PaymentStateTransitionException(
                    "Payment activation transition is not allowed");
        }
        if (leaseDuration == null
                || leaseDuration.isZero()
                || leaseDuration.isNegative()) {
            throw new PaymentOrderValidationException(
                    "Activation lease duration must be positive");
        }
        if (activationLeaseUntil == null
                || activationLeaseUntil.isAfter(now)) {
            throw new PaymentStateTransitionException(
                    "Activation lease has not expired");
        }
        if (maxAttempts <= 0 || activationAttempts >= maxAttempts) {
            throw new PaymentStateTransitionException(
                    "Activation attempt limit reached");
        }
        Instant newLeaseUntil;
        try {
            newLeaseUntil = now.plus(leaseDuration);
        } catch (DateTimeException | ArithmeticException exception) {
            throw new PaymentOrderValidationException(
                    "Activation lease duration cannot be applied");
        }
        long newGeneration = nextActivationGeneration();
        activationClaimToken = newClaimToken;
        activationGeneration = newGeneration;
        activationLeaseUntil = newLeaseUntil;
        activationCompletedAt = null;
        activationAttempts++;
        updatedAt = now;
        return newGeneration;
    }

    public void fixActivationTargetExpiresAt(
            UUID claimToken,
            long claimGeneration,
            Instant target,
            Instant operationTime
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(operationTime, "operationTime");
        requireCurrentClaim(claimToken, claimGeneration);
        if (!target.isAfter(operationTime)) {
            throw new PaymentOrderValidationException(
                    "Activation target expiry must be in the future");
        }
        if (activationTargetExpiresAt == null) {
            activationTargetExpiresAt = target;
            updatedAt = operationTime;
            return;
        }
        if (!activationTargetExpiresAt.equals(target)) {
            throw new PaymentStateTransitionException(
                    "Activation target expiry is already fixed");
        }
    }

    public void releaseForRetry(
            UUID claimToken,
            long claimGeneration,
            Instant now
    ) {
        completeClaim(
                claimToken, claimGeneration,
                PaymentActivationStatus.RETRY_REQUIRED, now);
    }

    public void scheduleRetry(UUID claimToken, long claimGeneration, Instant now, Instant nextAttemptAt) {
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        requireCurrentClaim(claimToken, claimGeneration);
        if (nextAttemptAt.isBefore(now)) throw new PaymentOrderValidationException("Retry time must not be before now");
        nextActivationAt = nextAttemptAt.truncatedTo(ChronoUnit.MICROS);
        completeClaim(claimToken, claimGeneration, PaymentActivationStatus.RETRY_REQUIRED, now);
    }

    public void attachSubscription(Subscription subscription, Instant now) {
        Objects.requireNonNull(subscription, "subscription");
        if (this.subscription != null && !this.subscription.getId().equals(subscription.getId())) {
            throw new PaymentStateTransitionException("Payment order is already linked to another subscription");
        }
        this.subscription = subscription;
        this.updatedAt = now;
    }

    public void setSafeFailureCode(String failureCode) {
        this.safeFailureCode = validateFailureCode(failureCode);
    }

    public void markAttemptsExhausted(Instant now) {
        Objects.requireNonNull(now, "now");
        if (status != PaymentStatus.SUCCEEDED ||
                (activationStatus != PaymentActivationStatus.PENDING
                        && activationStatus != PaymentActivationStatus.RETRY_REQUIRED
                        && activationStatus != PaymentActivationStatus.PROCESSING)) {
            throw new PaymentStateTransitionException("Activation attempt limit transition is not allowed");
        }
        activationStatus = PaymentActivationStatus.MANUAL_REVIEW_REQUIRED;
        activationClaimToken = null;
        activationLeaseUntil = null;
        nextActivationAt = null;
        activationCompletedAt = null;
        safeFailureCode = "ACTIVATION_MAX_ATTEMPTS_REACHED";
        updatedAt = now;
    }

    public void markReconciliationRequired(
            UUID claimToken,
            long claimGeneration,
            Instant now
    ) {
        completeClaim(
                claimToken, claimGeneration,
                PaymentActivationStatus.RECONCILIATION_REQUIRED, now);
    }

    public void markActivated(
            UUID claimToken,
            long claimGeneration,
            Instant now
    ) {
        completeClaim(
                claimToken, claimGeneration,
                PaymentActivationStatus.ACTIVATED, now);
    }

    public void markActivationManualReviewRequired(
            UUID claimToken,
            long claimGeneration,
            Instant now
    ) {
        completeClaim(
                claimToken, claimGeneration,
                PaymentActivationStatus.MANUAL_REVIEW_REQUIRED, now);
    }

    private void completeClaim(
            UUID claimToken,
            long claimGeneration,
            PaymentActivationStatus target,
            Instant now
    ) {
        Instant validatedNow = Objects.requireNonNull(now, "now");
        requireCurrentClaim(claimToken, claimGeneration);
        activationStatus = target;
        activationClaimToken = null;
        activationLeaseUntil = null;
        if (target != PaymentActivationStatus.RETRY_REQUIRED) nextActivationAt = null;
        if (target != PaymentActivationStatus.RETRY_REQUIRED) safeFailureCode = null;
        activationCompletedAt = target == PaymentActivationStatus.ACTIVATED
                ? validatedNow.truncatedTo(ChronoUnit.MICROS) : null;
        updatedAt = validatedNow;
    }

    private void requireCurrentClaim(UUID claimToken, long claimGeneration) {
        if (activationStatus != PaymentActivationStatus.PROCESSING
                || claimToken == null
                || !claimToken.equals(activationClaimToken)
                || claimGeneration != activationGeneration) {
            throw new PaymentStateTransitionException(
                    "Activation claim is stale or missing");
        }
    }

    private void transitionPayment(
            PaymentStatus target,
            Instant now,
            PaymentStatus... allowedSources
    ) {
        Instant validatedNow = Objects.requireNonNull(now, "now");
        requirePaymentState(allowedSources);
        status = target;
        updatedAt = validatedNow;
    }

    private long nextActivationGeneration() {
        try {
            return Math.incrementExact(activationGeneration);
        } catch (ArithmeticException exception) {
            throw new PaymentStateTransitionException(
                    "Activation generation limit reached");
        }
    }

    private void requirePaymentState(PaymentStatus... allowed) {
        for (PaymentStatus candidate : allowed) {
            if (status == candidate) {
                return;
            }
        }
        throw new PaymentStateTransitionException(
                "Payment status transition is not allowed");
    }

    private void requireActivationState(PaymentActivationStatus... allowed) {
        for (PaymentActivationStatus candidate : allowed) {
            if (activationStatus == candidate) {
                return;
            }
        }
        throw new PaymentStateTransitionException(
                "Payment activation transition is not allowed");
    }

    private static BigDecimal rubAmount(BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw new PaymentOrderValidationException("Amount must be positive");
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new PaymentOrderValidationException(
                    "RUB amount must not require rounding");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new PaymentOrderValidationException(field + " must not be blank");
        }
    }

    private static void validatePendingTtl(Duration pendingTtl) {
        if (pendingTtl == null
                || pendingTtl.isZero()
                || pendingTtl.isNegative()
                || pendingTtl.compareTo(MAX_PENDING_TTL) > 0) {
            throw new PaymentOrderValidationException(
                    "Pending TTL must be between 1 nanosecond and 24 hours");
        }
    }

    private static String validateFailureCode(String failureCode) {
        if (failureCode == null || failureCode.isBlank()) {
            return null;
        }
        if (failureCode.length() > FAILURE_CODE_MAX_LENGTH
                || !FAILURE_CODE_PATTERN.matcher(failureCode).matches()) {
            throw new PaymentOrderValidationException("Failure code is invalid");
        }
        return failureCode;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || effectiveClass(this) != effectiveClass(other)) {
            return false;
        }
        UUID thisId = identifier(this);
        return thisId != null && thisId.equals(identifier(other));
    }

    @Override
    public int hashCode() {
        return effectiveClass(this).hashCode();
    }

    private static Class<?> effectiveClass(Object value) {
        if (value instanceof HibernateProxy proxy) {
            return proxy.getHibernateLazyInitializer().getPersistentClass();
        }
        return value.getClass();
    }

    private static UUID identifier(Object value) {
        if (value instanceof HibernateProxy proxy) {
            return (UUID) proxy.getHibernateLazyInitializer().getIdentifier();
        }
        return ((PaymentOrder) value).id;
    }

    @Override
    public String toString() {
        return "PaymentOrder[provider=" + provider
                + ", status=" + status
                + ", activationStatus=" + activationStatus + "]";
    }
}
