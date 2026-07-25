package ru.murad.myvpn.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import ru.murad.myvpn.dto.CreatePaymentCommand;
import ru.murad.myvpn.dto.CreatedPayment;
import ru.murad.myvpn.dto.ProviderPayment;
import ru.murad.myvpn.exception.PaymentNotFoundException;
import ru.murad.myvpn.exception.PaymentProviderPermanentException;
import ru.murad.myvpn.model.ProviderPaymentStatus;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Component
@ConditionalOnProperty(name = "payment.provider", havingValue = "fake",
        matchIfMissing = true)
public class FakePaymentProvider implements PaymentProvider {

    private static final String ORDER_METADATA_KEY = "payment_order_id";

    private final Clock clock;
    private final Supplier<String> providerIdGenerator;
    private final ConcurrentHashMap<UUID, FakePayment> byIdempotence =
            new ConcurrentHashMap<>();

    @Autowired
    public FakePaymentProvider(Clock clock) {
        this(clock, () -> "fake_" + UUID.randomUUID());
    }

    FakePaymentProvider(Clock clock, Supplier<String> providerIdGenerator) {
        this.clock = clock;
        this.providerIdGenerator = providerIdGenerator;
    }

    @Override
    public CreatedPayment createPayment(CreatePaymentCommand command) {
        validate(command);
        FakePayment payment = byIdempotence.compute(
                command.idempotenceKey(),
                (key, existing) -> {
                    if (existing != null) {
                        if (!sameRequest(existing.command, command)) {
                            throw new PaymentProviderPermanentException(
                                    "Idempotence key was used with different parameters");
                        }
                        return existing;
                    }
                    String providerId = providerIdGenerator.get();
                    URI confirmation = PaymentConfirmationUrl.fake(URI.create(
                            "https://example.invalid/fake-pay/" + providerId)).value();
                    FakePayment created = new FakePayment(
                            command, providerId, confirmation, clock.instant());
                    return created;
                });
        return payment.created();
    }

    @Override
    public ProviderPayment getPayment(String providerPaymentId) {
        if (providerPaymentId == null || providerPaymentId.isBlank()) {
            throw new PaymentNotFoundException();
        }
        FakePayment payment = findByProviderId(providerPaymentId);
        if (payment == null) {
            throw new PaymentNotFoundException();
        }
        return payment.snapshot();
    }

    public void markSucceeded(String providerPaymentId) {
        updateStatus(providerPaymentId, ProviderPaymentStatus.SUCCEEDED);
    }

    public void markCanceled(String providerPaymentId) {
        updateStatus(providerPaymentId, ProviderPaymentStatus.CANCELED);
    }

    private void updateStatus(String providerPaymentId, ProviderPaymentStatus target) {
        FakePayment payment = findByProviderId(providerPaymentId);
        if (payment == null) {
            throw new PaymentNotFoundException();
        }
        payment.transition(target, clock.instant());
    }

    private FakePayment findByProviderId(String providerPaymentId) {
        return byIdempotence.values().stream()
                .filter(payment -> payment.providerId.equals(providerPaymentId))
                .findFirst()
                .orElse(null);
    }

    private boolean sameRequest(
            CreatePaymentCommand left,
            CreatePaymentCommand right
    ) {
        return left.paymentOrderId().equals(right.paymentOrderId())
                && left.amount().compareTo(right.amount()) == 0
                && Objects.equals(left.currency(), right.currency())
                && Objects.equals(left.description(), right.description())
                && Objects.equals(left.returnUrl(), right.returnUrl())
                && Objects.equals(left.metadata(), right.metadata());
    }

    private void validate(CreatePaymentCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.paymentOrderId(), "paymentOrderId");
        Objects.requireNonNull(command.idempotenceKey(), "idempotenceKey");
        BigDecimal amount = Objects.requireNonNull(command.amount(), "amount");
        if (amount.signum() <= 0 || !"RUB".equals(command.currency())) {
            throw new PaymentProviderPermanentException("Payment amount is invalid");
        }
        if (command.metadata() == null
                || !command.metadata().equals(Map.of(
                        ORDER_METADATA_KEY, command.paymentOrderId().toString()))) {
            throw new PaymentProviderPermanentException("Payment metadata is invalid");
        }
    }

    private static final class FakePayment {
        private final CreatePaymentCommand command;
        private final String providerId;
        private final URI confirmationUrl;
        private final Instant createdAt;
        private ProviderPaymentStatus status = ProviderPaymentStatus.PENDING;
        private Instant paidAt;

        private FakePayment(
                CreatePaymentCommand command,
                String providerId,
                URI confirmationUrl,
                Instant createdAt
        ) {
            this.command = command;
            this.providerId = providerId;
            this.confirmationUrl = confirmationUrl;
            this.createdAt = createdAt;
        }

        private synchronized void transition(
                ProviderPaymentStatus target,
                Instant transitionTime
        ) {
            if (status == target) {
                return;
            }
            if (status != ProviderPaymentStatus.PENDING) {
                throw new PaymentProviderPermanentException(
                        "Terminal fake payment status cannot be changed");
            }
            status = target;
            if (target == ProviderPaymentStatus.SUCCEEDED) {
                paidAt = transitionTime;
            }
        }

        private synchronized CreatedPayment created() {
            return new CreatedPayment(
                    providerId, status, confirmationUrl, createdAt, null);
        }

        private synchronized ProviderPayment snapshot() {
            return new ProviderPayment(
                    providerId,
                    status,
                    status == ProviderPaymentStatus.SUCCEEDED,
                    command.amount(),
                    command.currency(),
                    "fake",
                    command.paymentOrderId(),
                    null,
                    createdAt,
                    paidAt);
        }
    }
}
