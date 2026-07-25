package ru.murad.myvpn.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import ru.murad.myvpn.dto.CreatePaymentCommand;
import ru.murad.myvpn.exception.PaymentProviderPermanentException;
import ru.murad.myvpn.exception.PaymentNotFoundException;
import ru.murad.myvpn.model.ProviderPaymentStatus;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FakePaymentProviderTest {

    private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");

    @Test
    void createMustBeIdempotentAndReturnPendingPayment() {
        FakePaymentProvider provider = provider();
        CreatePaymentCommand command = command(UUID.randomUUID(), UUID.randomUUID(), "90.00");

        var first = provider.createPayment(command);
        var second = provider.createPayment(command);

        assertThat(second.providerPaymentId()).isEqualTo(first.providerPaymentId());
        assertThat(second.confirmationUrl()).isEqualTo(first.confirmationUrl());
        assertThat(second.status()).isEqualTo(ProviderPaymentStatus.PENDING);
        assertThat(first.confirmationUrl().getHost()).isEqualTo("example.invalid");
    }

    @Test
    void idempotenceKeyMustRejectDifferentAmountOrOrder() {
        FakePaymentProvider provider = provider();
        UUID key = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        provider.createPayment(command(order, key, "90.00"));

        assertThatThrownBy(() -> provider.createPayment(command(order, key, "91.00")))
                .isInstanceOf(PaymentProviderPermanentException.class);
        assertThatThrownBy(() -> provider.createPayment(
                command(UUID.randomUUID(), key, "90.00")))
                .isInstanceOf(PaymentProviderPermanentException.class);
    }

    @ParameterizedTest
    @MethodSource("idempotenceMismatches")
    void everySemanticCommandMismatchMustBeRejectedWithoutReplacingOriginal(
            UnaryOperator<CreatePaymentCommand> mutation
    ) {
        FakePaymentProvider provider = provider();
        CreatePaymentCommand original =
                command(UUID.randomUUID(), UUID.randomUUID(), "90.00");
        var created = provider.createPayment(original);

        assertThatThrownBy(() -> provider.createPayment(mutation.apply(original)))
                .isInstanceOf(PaymentProviderPermanentException.class)
                .hasMessageNotContaining(original.idempotenceKey().toString())
                .hasMessageNotContaining("payment_order_id");
        assertThat(provider.getPayment(created.providerPaymentId()).amount())
                .isEqualByComparingTo("90.00");
    }

    @Test
    void amountScaleMustNotChangeIdempotentRequest() {
        FakePaymentProvider provider = provider();
        UUID key = UUID.randomUUID();
        UUID order = UUID.randomUUID();

        var first = provider.createPayment(command(order, key, "10"));
        var second = provider.createPayment(command(order, key, "10.0"));
        var third = provider.createPayment(command(order, key, "10.00"));

        assertThat(second.providerPaymentId()).isEqualTo(first.providerPaymentId());
        assertThat(third.providerPaymentId()).isEqualTo(first.providerPaymentId());
    }

    @Test
    void failedAggregateCreationMustNotPublishOrphan() {
        AtomicInteger calls = new AtomicInteger();
        FakePaymentProvider provider = new FakePaymentProvider(
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> {
                    if (calls.getAndIncrement() == 0) {
                        throw new IllegalStateException("injected");
                    }
                    return "fake_safe_identifier_1234";
                });
        UUID key = UUID.randomUUID();
        UUID order = UUID.randomUUID();

        assertThatThrownBy(() -> provider.createPayment(command(order, key, "90.00")))
                .isInstanceOf(IllegalStateException.class);
        var created = provider.createPayment(command(order, key, "90.00"));

        assertThat(created.providerPaymentId())
                .isEqualTo("fake_safe_identifier_1234");
        assertThat(provider.getPayment(created.providerPaymentId())
                .providerPaymentId()).isEqualTo(created.providerPaymentId());
    }

    @Test
    void terminalStatusTransitionsMustBeIdempotentAndOneWay() {
        FakePaymentProvider succeededProvider = provider();
        var succeeded = succeededProvider.createPayment(
                command(UUID.randomUUID(), UUID.randomUUID(), "90.00"));
        succeededProvider.markSucceeded(succeeded.providerPaymentId());
        succeededProvider.markSucceeded(succeeded.providerPaymentId());

        assertThat(succeededProvider.getPayment(succeeded.providerPaymentId()).paid())
                .isTrue();
        assertThatThrownBy(() ->
                succeededProvider.markCanceled(succeeded.providerPaymentId()))
                .isInstanceOf(PaymentProviderPermanentException.class);

        FakePaymentProvider canceledProvider = provider();
        var canceled = canceledProvider.createPayment(
                command(UUID.randomUUID(), UUID.randomUUID(), "90.00"));
        canceledProvider.markCanceled(canceled.providerPaymentId());
        canceledProvider.markCanceled(canceled.providerPaymentId());
        assertThat(canceledProvider.getPayment(canceled.providerPaymentId()).status())
                .isEqualTo(ProviderPaymentStatus.CANCELED);
        assertThatThrownBy(() ->
                canceledProvider.markSucceeded(canceled.providerPaymentId()))
                .isInstanceOf(PaymentProviderPermanentException.class);
    }

    @Test
    void unknownProviderPaymentIdMustBeSafeForEveryOperation() {
        FakePaymentProvider provider = provider();
        for (Runnable operation : java.util.List.<Runnable>of(
                () -> provider.getPayment("missing"),
                () -> provider.markSucceeded("missing"),
                () -> provider.markCanceled("missing"))) {
            assertThatThrownBy(operation::run)
                    .isInstanceOf(PaymentNotFoundException.class)
                    .hasMessageNotContaining("missing");
        }
    }

    @Test
    void dtoToStringMustNotExposeProviderIdOrConfirmationUrl() {
        FakePaymentProvider provider = provider();
        CreatePaymentCommand command =
                command(UUID.randomUUID(), UUID.randomUUID(), "90.00");
        var created = provider.createPayment(command);
        var payment = provider.getPayment(created.providerPaymentId());

        assertThat(created.toString())
                .doesNotContain(created.providerPaymentId(), "example.invalid");
        assertThat(payment.toString()).doesNotContain(created.providerPaymentId());
        assertThat(command.toString())
                .doesNotContain(command.paymentOrderId().toString(),
                        command.idempotenceKey().toString(),
                        "payment_order_id");
        for (String representation : java.util.List.of(
                command.toString(),
                String.valueOf(command),
                Objects.toString(command),
                String.format("%s", command))) {
            assertThat(representation)
                    .doesNotContain(command.paymentOrderId().toString(),
                            command.idempotenceKey().toString());
        }
    }

    @Test
    void confirmationUrlMustRejectUnsafeComponents() {
        assertThatThrownBy(() -> PaymentConfirmationUrl.fake(
                URI.create("http://example.invalid/fake")))
                .isInstanceOf(PaymentProviderPermanentException.class);
        assertThatThrownBy(() -> PaymentConfirmationUrl.fake(
                URI.create("https://user@example.invalid/fake")))
                .isInstanceOf(PaymentProviderPermanentException.class);
        assertThatThrownBy(() -> PaymentConfirmationUrl.fake(
                URI.create("https://example.invalid/fake#fragment")))
                .isInstanceOf(PaymentProviderPermanentException.class);
        for (String value : java.util.List.of(
                "https://example.invalid:443/fake-pay/abcdefghijklmnop",
                "https://example.invalid/fake-pay/abcdefghijklmnop?x=1",
                "https://example.invalid.evil.com/fake-pay/abcdefghijklmnop",
                "https://evil-example.invalid/fake-pay/abcdefghijklmnop",
                "https://example.invalid./fake-pay/abcdefghijklmnop",
                "https://example.invalid/wrong/abcdefghijklmnop",
                "https://example.invalid/fake-pay/",
                "https://example.invalid/fake-pay/../abcdefghijklmnop")) {
            assertThatThrownBy(() -> PaymentConfirmationUrl.fake(URI.create(value)))
                    .isInstanceOf(PaymentProviderPermanentException.class);
        }
    }

    @Test
    void concurrentCreateWithSameKeyMustCreateOnePayment() throws Exception {
        FakePaymentProvider provider = provider();
        CreatePaymentCommand command = command(
                UUID.randomUUID(), UUID.randomUUID(), "90.00");
        CyclicBarrier barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return provider.createPayment(command);
            });
            var second = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return provider.createPayment(command);
            });

            assertThat(first.get(5, TimeUnit.SECONDS).providerPaymentId())
                    .isEqualTo(second.get(5, TimeUnit.SECONDS).providerPaymentId());
        } finally {
            executor.shutdownNow();
        }
    }

    private FakePaymentProvider provider() {
        return new FakePaymentProvider(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CreatePaymentCommand command(UUID orderId, UUID key, String amount) {
        return new CreatePaymentCommand(
                orderId,
                key,
                new BigDecimal(amount),
                "RUB",
                "VPN subscription",
                URI.create("https://example.invalid/return"),
                Map.of("payment_order_id", orderId.toString()));
    }

    private static Stream<UnaryOperator<CreatePaymentCommand>>
    idempotenceMismatches() {
        return Stream.of(
                command -> new CreatePaymentCommand(
                        UUID.randomUUID(), command.idempotenceKey(), command.amount(),
                        command.currency(), command.description(), command.returnUrl(),
                        command.metadata()),
                command -> new CreatePaymentCommand(
                        command.paymentOrderId(), command.idempotenceKey(),
                        command.amount().add(new BigDecimal("0.01")),
                        command.currency(), command.description(), command.returnUrl(),
                        command.metadata()),
                command -> new CreatePaymentCommand(
                        command.paymentOrderId(), command.idempotenceKey(), command.amount(),
                        "USD", command.description(), command.returnUrl(), command.metadata()),
                command -> new CreatePaymentCommand(
                        command.paymentOrderId(), command.idempotenceKey(), command.amount(),
                        command.currency(), "different", command.returnUrl(),
                        command.metadata()),
                command -> new CreatePaymentCommand(
                        command.paymentOrderId(), command.idempotenceKey(), command.amount(),
                        command.currency(), command.description(),
                        URI.create("https://example.invalid/other"), command.metadata()),
                command -> new CreatePaymentCommand(
                        command.paymentOrderId(), command.idempotenceKey(), command.amount(),
                        command.currency(), command.description(), command.returnUrl(),
                        Map.of("payment_order_id", UUID.randomUUID().toString())));
    }
}
