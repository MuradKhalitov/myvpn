package ru.murad.myvpn.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import ru.murad.myvpn.client.PaymentProvider;
import ru.murad.myvpn.config.PaymentProperties;
import ru.murad.myvpn.dto.PaymentVerificationOutcome;
import ru.murad.myvpn.dto.PaymentVerificationPreparation;
import ru.murad.myvpn.dto.PaymentVerificationResult;
import ru.murad.myvpn.dto.PreparedPaymentVerification;
import ru.murad.myvpn.dto.ProviderPayment;
import ru.murad.myvpn.exception.PaymentNotFoundException;
import ru.murad.myvpn.exception.PaymentProviderPermanentException;
import ru.murad.myvpn.exception.PaymentProviderUncertainException;
import ru.murad.myvpn.exception.ProviderPaymentValidationException;
import ru.murad.myvpn.model.PaymentActivationStatus;
import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.model.PaymentStatus;
import ru.murad.myvpn.model.ProviderPaymentStatus;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PaymentVerificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-25T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TARIFF_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID IDEMPOTENCE = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Test
    void trustedSuccessUsesSnapshotProviderAndExactOrchestrationOrder() {
        PaymentVerificationTransactionService tx = mock(PaymentVerificationTransactionService.class);
        PaymentProviderRegistry registry = mock(PaymentProviderRegistry.class);
        PaymentProvider provider = mock(PaymentProvider.class);
        ProviderPaymentValidator validator = mock(ProviderPaymentValidator.class);
        PreparedPaymentVerification expected = expected(PaymentStatus.PENDING);
        ProviderPayment actual = payment(ProviderPaymentStatus.SUCCEEDED, true,
                NOW.minusSeconds(1), NOW);
        PaymentVerificationResult success = result(PaymentVerificationOutcome.SUCCEEDED,
                PaymentStatus.SUCCEEDED, PaymentActivationStatus.PENDING);
        when(tx.prepare(77L, NOW, Duration.ofSeconds(5)))
                .thenReturn(new PaymentVerificationPreparation(expected, null));
        when(registry.resolve(PaymentProviderType.FAKE)).thenReturn(provider);
        when(provider.getPayment("fake-payment")).thenReturn(actual);
        when(tx.apply(expected, actual, NOW)).thenReturn(success);

        PaymentVerificationResult result = service(tx, registry, validator).verifyCurrentPayment(77L);

        assertThat(result).isSameAs(success);
        InOrder order = inOrder(tx, registry, provider, validator);
        order.verify(tx).prepare(77L, NOW, Duration.ofSeconds(5));
        order.verify(registry).resolve(PaymentProviderType.FAKE);
        order.verify(provider).getPayment("fake-payment");
        order.verify(validator).validate(expected, actual, NOW);
        order.verify(tx).apply(expected, actual, NOW);
        verify(tx, never()).manualReview(any(), anyString(), any());
        verify(provider).getPayment("fake-payment");
        verify(tx).apply(expected, actual, NOW);
    }

    @ParameterizedTest
    @MethodSource("immediateOutcomes")
    void immediatePreparationOutcomeDoesNotCallProviderFlow(PaymentVerificationResult immediate) {
        PaymentVerificationTransactionService tx = mock(PaymentVerificationTransactionService.class);
        PaymentProviderRegistry registry = mock(PaymentProviderRegistry.class);
        ProviderPaymentValidator validator = mock(ProviderPaymentValidator.class);
        when(tx.prepare(anyLong(), eq(NOW), eq(Duration.ofSeconds(5))))
                .thenReturn(new PaymentVerificationPreparation(null, immediate));

        assertThat(service(tx, registry, validator).verifyCurrentPayment(77L)).isSameAs(immediate);
        verifyNoInteractions(registry, validator);
        verify(tx, never()).apply(any(), any(), any());
        verify(tx, never()).manualReview(any(), anyString(), any());
    }

    @Test
    void trustedPendingAndCanceledUseTx2Apply() {
        for (ProviderPaymentStatus status : new ProviderPaymentStatus[]{
                ProviderPaymentStatus.PENDING, ProviderPaymentStatus.CANCELED}) {
            PaymentVerificationTransactionService tx = mock(PaymentVerificationTransactionService.class);
            PaymentProviderRegistry registry = mock(PaymentProviderRegistry.class);
            PaymentProvider provider = mock(PaymentProvider.class);
            ProviderPaymentValidator validator = mock(ProviderPaymentValidator.class);
            PreparedPaymentVerification expected = expected(PaymentStatus.PENDING);
            ProviderPayment actual = payment(status, false, NOW.minusSeconds(1), null);
            PaymentVerificationOutcome outcome = status == ProviderPaymentStatus.PENDING
                    ? PaymentVerificationOutcome.STILL_PENDING : PaymentVerificationOutcome.CANCELED;
            when(tx.prepare(anyLong(), eq(NOW), eq(Duration.ofSeconds(5))))
                    .thenReturn(new PaymentVerificationPreparation(expected, null));
            when(registry.resolve(PaymentProviderType.FAKE)).thenReturn(provider);
            when(provider.getPayment("fake-payment")).thenReturn(actual);
            when(tx.apply(expected, actual, NOW)).thenReturn(result(outcome, PaymentStatus.PENDING,
                    PaymentActivationStatus.NOT_READY));

            assertThat(service(tx, registry, validator).verifyCurrentPayment(77L).outcome())
                    .isEqualTo(outcome);
            verify(tx).apply(expected, actual, NOW);
            verify(tx, never()).manualReview(any(), anyString(), any());
        }
    }

    @Test
    void paymentNotFoundBecomesManualReviewWithoutApply() {
        PaymentVerificationTransactionService tx = mock(PaymentVerificationTransactionService.class);
        PaymentProviderRegistry registry = mock(PaymentProviderRegistry.class);
        PaymentProvider provider = mock(PaymentProvider.class);
        ProviderPaymentValidator validator = mock(ProviderPaymentValidator.class);
        PreparedPaymentVerification expected = expected(PaymentStatus.PENDING);
        PaymentVerificationResult manual = result(PaymentVerificationOutcome.MANUAL_REVIEW_REQUIRED,
                PaymentStatus.MANUAL_REVIEW_REQUIRED, PaymentActivationStatus.NOT_READY);
        prepare(tx, expected);
        when(registry.resolve(PaymentProviderType.FAKE)).thenReturn(provider);
        when(provider.getPayment("fake-payment")).thenThrow(new PaymentNotFoundException());
        when(tx.manualReview(expected, "PROVIDER_PAYMENT_NOT_FOUND", NOW)).thenReturn(manual);

        assertThat(service(tx, registry, validator).verifyCurrentPayment(77L)).isSameAs(manual);
        verify(tx).manualReview(expected, "PROVIDER_PAYMENT_NOT_FOUND", NOW);
        verify(tx, never()).apply(any(), any(), any());
        verifyNoInteractions(validator);
        assertThat(manual.toString()).doesNotContain("fake-payment", ORDER_ID.toString());
    }

    @ParameterizedTest
    @MethodSource("providerExceptions")
    void providerExceptionsBecomeSafeUnavailableOutcome(RuntimeException exception) {
        PaymentVerificationTransactionService tx = mock(PaymentVerificationTransactionService.class);
        PaymentProviderRegistry registry = mock(PaymentProviderRegistry.class);
        PaymentProvider provider = mock(PaymentProvider.class);
        ProviderPaymentValidator validator = mock(ProviderPaymentValidator.class);
        prepare(tx, expected(PaymentStatus.PENDING));
        when(registry.resolve(PaymentProviderType.FAKE)).thenReturn(provider);
        when(provider.getPayment("fake-payment")).thenThrow(exception);

        PaymentVerificationResult result = service(tx, registry, validator).verifyCurrentPayment(77L);

        assertThat(result.outcome()).isEqualTo(PaymentVerificationOutcome.PROVIDER_UNAVAILABLE);
        assertThat(result.toString()).doesNotContain("secret", "fake-payment", "https://");
        verifyNoInteractions(validator);
        verify(tx, never()).apply(any(), any(), any());
        verify(tx, never()).manualReview(any(), anyString(), any());
    }

    @Test
    void unexpectedProviderRuntimeExceptionIsNotMisclassifiedAsUnavailable() {
        PaymentVerificationTransactionService tx = mock(PaymentVerificationTransactionService.class);
        PaymentProviderRegistry registry = mock(PaymentProviderRegistry.class);
        PaymentProvider provider = mock(PaymentProvider.class);
        prepare(tx, expected(PaymentStatus.PENDING));
        when(registry.resolve(PaymentProviderType.FAKE)).thenReturn(provider);
        IllegalStateException failure = new IllegalStateException("secret fake-payment https://example.invalid");
        when(provider.getPayment("fake-payment")).thenThrow(failure);

        assertThatThrownBy(() -> service(tx, registry, mock(ProviderPaymentValidator.class))
                .verifyCurrentPayment(77L)).isSameAs(failure);
    }

    @ParameterizedTest
    @MethodSource("tx2Exceptions")
    void tx2ExceptionsAreNotReclassifiedAsProviderFailures(RuntimeException failure) {
        PaymentVerificationTransactionService tx = mock(PaymentVerificationTransactionService.class);
        PaymentProviderRegistry registry = mock(PaymentProviderRegistry.class);
        PaymentProvider provider = mock(PaymentProvider.class);
        ProviderPaymentValidator validator = mock(ProviderPaymentValidator.class);
        PreparedPaymentVerification expected = expected(PaymentStatus.PENDING);
        ProviderPayment actual = payment(ProviderPaymentStatus.PENDING, false, NOW.minusSeconds(1), null);
        prepare(tx, expected);
        when(registry.resolve(PaymentProviderType.FAKE)).thenReturn(provider);
        when(provider.getPayment("fake-payment")).thenReturn(actual);
        when(tx.apply(expected, actual, NOW)).thenThrow(failure);

        assertThatThrownBy(() -> service(tx, registry, validator).verifyCurrentPayment(77L))
                .isSameAs(failure);
        verify(tx, never()).manualReview(any(), anyString(), any());
    }

    @Test
    void validatorProviderExceptionIsNotClassifiedAsGetFailure() {
        PaymentVerificationTransactionService tx = mock(PaymentVerificationTransactionService.class);
        PaymentProviderRegistry registry = mock(PaymentProviderRegistry.class);
        PaymentProvider provider = mock(PaymentProvider.class);
        ProviderPaymentValidator validator = mock(ProviderPaymentValidator.class);
        PreparedPaymentVerification expected = expected(PaymentStatus.PENDING);
        ProviderPayment actual = payment(ProviderPaymentStatus.PENDING, false, NOW.minusSeconds(1), null);
        prepare(tx, expected);
        when(registry.resolve(PaymentProviderType.FAKE)).thenReturn(provider);
        when(provider.getPayment("fake-payment")).thenReturn(actual);
        PaymentProviderUncertainException failure = new PaymentProviderUncertainException("validator failure");
        doThrow(failure).when(validator).validate(expected, actual, NOW);

        assertThatThrownBy(() -> service(tx, registry, validator).verifyCurrentPayment(77L))
                .isSameAs(failure);
        verify(tx, never()).apply(any(), any(), any());
    }

    @Test
    void policyProviderExceptionIsNotClassifiedAsGetFailure() {
        PaymentVerificationTransactionService tx = mock(PaymentVerificationTransactionService.class);
        PaymentProviderRegistry registry = mock(PaymentProviderRegistry.class);
        PaymentProvider provider = mock(PaymentProvider.class);
        ProviderPaymentVerificationPolicyRegistry policies = mock(ProviderPaymentVerificationPolicyRegistry.class);
        ProviderPaymentVerificationPolicy policy = mock(ProviderPaymentVerificationPolicy.class);
        PreparedPaymentVerification expected = expected(PaymentStatus.PENDING);
        ProviderPayment actual = payment(ProviderPaymentStatus.PENDING, false, NOW.minusSeconds(1), null);
        prepare(tx, expected);
        when(registry.resolve(PaymentProviderType.FAKE)).thenReturn(provider);
        when(provider.getPayment("fake-payment")).thenReturn(actual);
        when(policies.resolve(PaymentProviderType.FAKE)).thenReturn(policy);
        doThrow(new PaymentProviderUncertainException("policy failure"))
                .when(policy).validate(expected, actual, NOW);

        assertThatThrownBy(() -> service(tx, registry,
                new ProviderPaymentValidator(policies, properties())).verifyCurrentPayment(77L))
                .isInstanceOf(PaymentProviderUncertainException.class);
        verify(tx, never()).apply(any(), any(), any());
    }

    @Test
    void preparationInfrastructureExceptionIsNotProviderUnavailable() {
        PaymentVerificationTransactionService tx = mock(PaymentVerificationTransactionService.class);
        RuntimeException failure = new IllegalStateException("database failure");
        when(tx.prepare(anyLong(), any(), any())).thenThrow(failure);

        assertThatThrownBy(() -> service(tx, mock(PaymentProviderRegistry.class),
                mock(ProviderPaymentValidator.class)).verifyCurrentPayment(77L))
                .isSameAs(failure);
    }

    @ParameterizedTest
    @MethodSource("validationFailures")
    void validationFailureUsesManualReview(String code) {
        PaymentVerificationTransactionService tx = mock(PaymentVerificationTransactionService.class);
        PaymentProviderRegistry registry = mock(PaymentProviderRegistry.class);
        PaymentProvider provider = mock(PaymentProvider.class);
        ProviderPaymentValidator validator = mock(ProviderPaymentValidator.class);
        PreparedPaymentVerification expected = expected(PaymentStatus.PENDING);
        ProviderPayment actual = payment(ProviderPaymentStatus.PENDING, false, NOW.minusSeconds(1), null);
        prepare(tx, expected);
        when(registry.resolve(PaymentProviderType.FAKE)).thenReturn(provider);
        when(provider.getPayment("fake-payment")).thenReturn(actual);
        doThrow(new ProviderPaymentValidationException(code, true))
                .when(validator).validate(expected, actual, NOW);
        PaymentVerificationResult manual = result(PaymentVerificationOutcome.MANUAL_REVIEW_REQUIRED,
                PaymentStatus.MANUAL_REVIEW_REQUIRED, PaymentActivationStatus.NOT_READY);
        when(tx.manualReview(expected, code, NOW)).thenReturn(manual);

        assertThat(service(tx, registry, validator).verifyCurrentPayment(77L)).isSameAs(manual);
        verify(tx).manualReview(expected, code, NOW);
        verify(tx, never()).apply(any(), any(), any());
    }

    @Test
    void validatorDelegatesProviderSpecificPolicy() {
        ProviderPaymentVerificationPolicyRegistry policies = mock(ProviderPaymentVerificationPolicyRegistry.class);
        ProviderPaymentVerificationPolicy policy = mock(ProviderPaymentVerificationPolicy.class);
        PaymentProperties properties = properties();
        ProviderPaymentValidator validator = new ProviderPaymentValidator(policies, properties);
        PreparedPaymentVerification expected = expected(PaymentStatus.PENDING);
        ProviderPayment actual = payment(ProviderPaymentStatus.PENDING, false, NOW.minusSeconds(1), null);
        when(policies.resolve(PaymentProviderType.FAKE)).thenReturn(policy);
        when(policy.providerType()).thenReturn(PaymentProviderType.FAKE);

        validator.validate(expected, actual, NOW);

        verify(policies).resolve(PaymentProviderType.FAKE);
        verify(policy).validate(expected, actual, NOW);
    }

    @Test
    void allInternalDtoRenderingIsSafe() {
        String rendered = String.valueOf(expected(PaymentStatus.PENDING))
                + String.valueOf(result(PaymentVerificationOutcome.SUCCEEDED,
                PaymentStatus.SUCCEEDED, PaymentActivationStatus.PENDING));
        assertThat(rendered).doesNotContain(ORDER_ID.toString(), USER_ID.toString(),
                "fake-payment", IDEMPOTENCE.toString(), "secret", "https://");
    }

    static Stream<PaymentVerificationResult> immediateOutcomes() {
        return Stream.of(PaymentVerificationOutcome.ALREADY_SUCCEEDED,
                        PaymentVerificationOutcome.ALREADY_CANCELED,
                        PaymentVerificationOutcome.TERMINAL,
                        PaymentVerificationOutcome.TOO_EARLY,
                        PaymentVerificationOutcome.CHECKOUT_INCOMPLETE,
                        PaymentVerificationOutcome.MANUAL_REVIEW_REQUIRED,
                        PaymentVerificationOutcome.AMBIGUOUS_PAYMENT_STATE,
                        PaymentVerificationOutcome.NOT_FOUND)
                .map(outcome -> result(outcome, PaymentStatus.PENDING,
                        PaymentActivationStatus.NOT_READY));
    }

    static Stream<RuntimeException> providerExceptions() {
        return Stream.of(new PaymentProviderUncertainException("secret fake-payment https://example.invalid"),
                new PaymentProviderPermanentException("secret fake-payment https://example.invalid"));
    }

    static Stream<RuntimeException> tx2Exceptions() {
        return Stream.of(new PaymentNotFoundException(),
                new PaymentProviderUncertainException("tx2 uncertain"),
                new DataIntegrityViolationException("tx2 integrity"),
                new ObjectOptimisticLockingFailureException("tx2 optimistic", ORDER_ID));
    }

    static Stream<String> validationFailures() {
        return Stream.of("PROVIDER_PAYMENT_ID_MISMATCH", "PAYMENT_AMOUNT_MISMATCH",
                "PAYMENT_CURRENCY_MISMATCH", "PAYMENT_METADATA_MISMATCH",
                "PROVIDER_STATUS_PAID_MISMATCH", "PROVIDER_TIMESTAMP_MISMATCH",
                "PAYMENT_METHOD_MISMATCH");
    }

    private PaymentVerificationService service(PaymentVerificationTransactionService tx,
                                                PaymentProviderRegistry registry,
                                                ProviderPaymentValidator validator) {
        return new ru.murad.myvpn.service.impl.PaymentVerificationServiceImpl(
                tx, registry, validator, properties(), CLOCK);
    }

    private void prepare(PaymentVerificationTransactionService tx, PreparedPaymentVerification expected) {
        when(tx.prepare(77L, NOW, Duration.ofSeconds(5)))
                .thenReturn(new PaymentVerificationPreparation(expected, null));
    }

    private PreparedPaymentVerification expected(PaymentStatus status) {
        return new PreparedPaymentVerification(ORDER_ID, USER_ID, PaymentProviderType.FAKE,
                "fake-payment", new BigDecimal("100.00"), "RUB", TARIFF_ID,
                "MONTH_1", "Monthly", 30, status, IDEMPOTENCE);
    }

    private ProviderPayment payment(ProviderPaymentStatus status, boolean paid,
                                    Instant createdAt, Instant paidAt) {
        return new ProviderPayment("fake-payment", status, paid, new BigDecimal("100.00"),
                "RUB", "fake", ORDER_ID, null, createdAt, paidAt);
    }

    private static PaymentVerificationResult result(PaymentVerificationOutcome outcome,
                                             PaymentStatus status,
                                             PaymentActivationStatus activation) {
        return new PaymentVerificationResult(outcome, status, activation, null, null);
    }

    private PaymentProperties properties() {
        return new PaymentProperties(PaymentProviderType.FAKE, Duration.ofHours(1),
                URI.create("https://example.invalid/return"), true,
                new PaymentProperties.Verification(Duration.ofSeconds(5), Duration.ofMinutes(5)));
    }
}
