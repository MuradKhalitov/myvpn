package ru.murad.myvpn.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.murad.myvpn.client.PaymentProvider;
import ru.murad.myvpn.config.PaymentProperties;
import ru.murad.myvpn.dto.CreatePaymentCommand;
import ru.murad.myvpn.dto.CreatedPayment;
import ru.murad.myvpn.dto.PaymentCheckoutResult;
import ru.murad.myvpn.dto.PreparedCheckout;
import ru.murad.myvpn.dto.ProviderPayment;
import ru.murad.myvpn.exception.PaymentProviderPermanentException;
import ru.murad.myvpn.exception.PaymentProviderUncertainException;
import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.model.PaymentStatus;
import ru.murad.myvpn.model.ProviderPaymentStatus;
import ru.murad.myvpn.repository.PaymentOrderRepository;
import ru.murad.myvpn.repository.TelegramUserRepository;
import ru.murad.myvpn.service.PaymentCheckoutTransactionService;
import ru.murad.myvpn.service.PaymentProviderRegistry;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentCheckoutServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");
    private static final URI URL = URI.create(
            "https://example.invalid/fake-pay/abcdefghijklmnop");

    @Mock private TelegramUserRepository userRepository;
    @Mock private PaymentOrderRepository orderRepository;
    @Mock private PaymentProviderRegistry registry;
    @Mock private PaymentProvider provider;
    @Mock private PaymentCheckoutTransactionService transactionService;

    @BeforeEach
    void setUp() {
        when(transactionService.prepareCheckout(
                10L, "MONTH_1", PaymentProviderType.FAKE,
                Duration.ofHours(1), NOW)).thenReturn(prepared(PaymentStatus.CREATING));
    }

    @Test
    void createMustApplyProviderResultInSecondCoordinatorCall() {
        CreatedPayment created = created();
        PaymentCheckoutResult expected = result();
        when(registry.resolve(PaymentProviderType.FAKE)).thenReturn(provider);
        when(provider.createPayment(any())).thenReturn(created);
        when(transactionService.applyCreatedPayment(any(), any(), any()))
                .thenReturn(expected);

        assertThat(service().startCheckout(10L, "MONTH_1")).isSameAs(expected);
        verify(transactionService).applyCreatedPayment(
                prepared(PaymentStatus.CREATING), created, NOW);
    }

    @Test
    void providerCallMustRunWithoutActiveDatabaseTransaction() {
        when(registry.resolve(PaymentProviderType.FAKE)).thenReturn(provider);
        when(provider.createPayment(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager
                    .isActualTransactionActive()).isFalse();
            return created();
        });
        when(transactionService.applyCreatedPayment(any(), any(), any()))
                .thenReturn(result());

        service().startCheckout(10L, "MONTH_1");
    }

    @Test
    void pendingCheckoutMustNotCallProvider() {
        when(transactionService.prepareCheckout(
                10L, "MONTH_1", PaymentProviderType.FAKE,
                Duration.ofHours(1), NOW)).thenReturn(prepared(PaymentStatus.PENDING));

        assertThat(service().startCheckout(10L, "MONTH_1").status())
                .isEqualTo(PaymentStatus.PENDING);
        verify(registry, never()).resolve(any());
    }

    @Test
    void uncertainProviderFailureMustNotMarkPermanentFailure() {
        when(registry.resolve(PaymentProviderType.FAKE)).thenReturn(provider);
        when(provider.createPayment(any()))
                .thenThrow(new PaymentProviderUncertainException("temporary"));

        assertThatThrownBy(() -> service().startCheckout(10L, "MONTH_1"))
                .isInstanceOf(PaymentProviderUncertainException.class);
        verify(transactionService, never()).markPermanentFailure(any(), any());
    }

    @Test
    void permanentRejectionMustUseSeparateFailureTransaction() {
        when(registry.resolve(PaymentProviderType.FAKE)).thenReturn(provider);
        when(provider.createPayment(any()))
                .thenThrow(new PaymentProviderPermanentException("rejected"));

        assertThatThrownBy(() -> service().startCheckout(10L, "MONTH_1"))
                .isInstanceOf(PaymentProviderPermanentException.class);
        verify(transactionService).markPermanentFailure(
                prepared(PaymentStatus.CREATING), NOW);
    }

    @Test
    void retryMustUseSameOrderAndIdempotenceKey() {
        when(registry.resolve(PaymentProviderType.FAKE)).thenReturn(provider);
        when(provider.createPayment(any()))
                .thenThrow(new PaymentProviderUncertainException("temporary"));

        assertThatThrownBy(() -> service().startCheckout(10L, "MONTH_1"))
                .isInstanceOf(PaymentProviderUncertainException.class);
        assertThatThrownBy(() -> service().startCheckout(10L, "MONTH_1"))
                .isInstanceOf(PaymentProviderUncertainException.class);

        ArgumentCaptor<CreatePaymentCommand> commands =
                ArgumentCaptor.forClass(CreatePaymentCommand.class);
        verify(provider, org.mockito.Mockito.times(2)).createPayment(commands.capture());
        assertThat(commands.getAllValues())
                .extracting(CreatePaymentCommand::idempotenceKey)
                .containsOnly(prepared(PaymentStatus.CREATING).idempotenceKey());
        assertThat(commands.getAllValues())
                .extracting(CreatePaymentCommand::paymentOrderId)
                .containsOnly(prepared(PaymentStatus.CREATING).orderId());
    }

    @Test
    void optimisticFailureFromTx2MustNotBecomeProviderUncertain() {
        when(registry.resolve(PaymentProviderType.FAKE)).thenReturn(provider);
        when(provider.createPayment(any())).thenReturn(created());
        when(transactionService.applyCreatedPayment(any(), any(), any()))
                .thenThrow(new ObjectOptimisticLockingFailureException("order", UUID.randomUUID()));

        assertThatThrownBy(() -> service().startCheckout(10L, "MONTH_1"))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void persistenceFailureFromTx2MustNotBecomeProviderUncertain() {
        when(registry.resolve(PaymentProviderType.FAKE)).thenReturn(provider);
        when(provider.createPayment(any())).thenReturn(created());
        when(transactionService.applyCreatedPayment(any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("safe-test"));

        assertThatThrownBy(() -> service().startCheckout(10L, "MONTH_1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private PaymentCheckoutServiceImpl service() {
        return new PaymentCheckoutServiceImpl(
                userRepository, orderRepository, registry, transactionService,
                new PaymentProperties(PaymentProviderType.FAKE, Duration.ofHours(1),
                        URI.create("https://example.invalid/return"), true),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PreparedCheckout prepared(PaymentStatus status) {
        URI url = status == PaymentStatus.PENDING ? URL : null;
        return new PreparedCheckout(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                PaymentProviderType.FAKE,
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                new BigDecimal("90.00"), "RUB", "MONTH_1", "Month", 30,
                status, url, NOW.plusSeconds(3600));
    }

    private CreatedPayment created() {
        return new CreatedPayment(
                "fake-payment", ProviderPaymentStatus.PENDING, URL, NOW, null);
    }

    private PaymentCheckoutResult result() {
        PreparedCheckout value = prepared(PaymentStatus.PENDING);
        return new PaymentCheckoutResult(
                value.orderId(), value.tariffName(), value.amount(),
                value.currency(), value.durationDays(), value.status(),
                value.confirmationUrl(), value.localExpiresAt());
    }
}
