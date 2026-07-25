package ru.murad.myvpn.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.murad.myvpn.dto.CreatedPayment;
import ru.murad.myvpn.dto.PreparedCheckout;
import ru.murad.myvpn.exception.PaymentProviderUncertainException;
import ru.murad.myvpn.model.PaymentOrder;
import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.model.PaymentStatus;
import ru.murad.myvpn.model.ProviderPaymentStatus;
import ru.murad.myvpn.model.TelegramUser;
import ru.murad.myvpn.model.UserRole;
import ru.murad.myvpn.model.VpnTariff;
import ru.murad.myvpn.repository.PaymentOrderRepository;
import ru.murad.myvpn.repository.TelegramUserRepository;
import ru.murad.myvpn.repository.VpnTariffRepository;
import ru.murad.myvpn.service.CreatedPaymentValidator;
import ru.murad.myvpn.service.PaymentOrderCreationService;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentCheckoutTransactionServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");
    private static final URI URL = URI.create(
            "https://example.invalid/fake-pay/abcdefghijklmnop");

    @Mock private TelegramUserRepository userRepository;
    @Mock private VpnTariffRepository tariffRepository;
    @Mock private PaymentOrderRepository orderRepository;
    @Mock private PaymentOrderCreationService orderCreationService;
    @Mock private CreatedPaymentValidator validator;
    private PaymentOrder order;

    @BeforeEach
    void setUp() {
        TelegramUser user = TelegramUser.builder()
                .id(UUID.randomUUID()).telegramId(10L).chatId(10L)
                .role(UserRole.USER).createdAt(NOW).updatedAt(NOW).build();
        VpnTariff tariff = VpnTariff.builder()
                .id(UUID.randomUUID()).code("MONTH_1").name("Month")
                .durationDays(30).price(new BigDecimal("90.00")).currency("RUB")
                .active(true).createdAt(NOW).updatedAt(NOW).build();
        order = PaymentOrder.create(
                user, tariff, PaymentProviderType.FAKE, NOW, Duration.ofHours(1));
        order.markCreating(NOW);
    }

    @Test
    void applyMustReloadWithPessimisticLockAndNotMergeDetachedOrder() {
        when(orderRepository.findByIdForUpdate(order.getId()))
                .thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        service().applyCreatedPayment(prepared(), created(), NOW);

        verify(orderRepository).findByIdForUpdate(order.getId());
        verify(orderRepository).saveAndFlush(order);
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void domainValidationAfterDtoValidationMustBecomeUncertainWithoutMutation() {
        when(orderRepository.findByIdForUpdate(order.getId()))
                .thenReturn(Optional.of(order));
        CreatedPayment oversized = new CreatedPayment(
                "x".repeat(129), ProviderPaymentStatus.PENDING, URL, NOW, null);
        Instant updatedAt = order.getUpdatedAt();

        assertThatThrownBy(() -> service().applyCreatedPayment(
                prepared(), oversized, NOW))
                .isInstanceOf(PaymentProviderUncertainException.class);
        assertThat(order.getStatus()).isEqualTo(PaymentStatus.CREATING);
        assertThat(order.getProviderPaymentId()).isNull();
        assertThat(order.getUpdatedAt()).isEqualTo(updatedAt);
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    void permanentFailureMustNotOverwriteAlreadyPendingOrder() {
        order.markPending("fake-payment", URL.toString(), NOW, null, NOW);
        when(orderRepository.findByIdForUpdate(order.getId()))
                .thenReturn(Optional.of(order));

        service().markPermanentFailure(prepared(), NOW.plusSeconds(1));

        assertThat(order.getStatus()).isEqualTo(PaymentStatus.PENDING);
        verify(orderRepository, never()).saveAndFlush(any());
    }

    private PaymentCheckoutTransactionServiceImpl service() {
        return new PaymentCheckoutTransactionServiceImpl(
                userRepository, tariffRepository, orderRepository,
                orderCreationService, validator);
    }

    private PreparedCheckout prepared() {
        return new PreparedCheckout(
                order.getId(), order.getUser().getId(), order.getTariff().getId(),
                order.getProvider(), order.getIdempotenceKey(), order.getAmount(),
                order.getCurrency(), order.getTariffCodeSnapshot(),
                order.getTariffNameSnapshot(), order.getDurationDaysSnapshot(),
                PaymentStatus.CREATING, null, order.getExpiresAt());
    }

    private CreatedPayment created() {
        return new CreatedPayment(
                "fake-payment", ProviderPaymentStatus.PENDING, URL, NOW, null);
    }
}
