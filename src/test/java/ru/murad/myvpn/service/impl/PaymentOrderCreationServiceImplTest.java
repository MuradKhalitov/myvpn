package ru.murad.myvpn.service.impl;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import ru.murad.myvpn.exception.OpenPaymentOrderAlreadyExistsException;
import ru.murad.myvpn.exception.PaymentOrderPersistenceException;
import ru.murad.myvpn.model.PaymentOrder;
import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.model.TelegramUser;
import ru.murad.myvpn.model.UserRole;
import ru.murad.myvpn.model.VpnTariff;
import ru.murad.myvpn.repository.PaymentOrderRepository;
import ru.murad.myvpn.repository.TelegramUserRepository;
import ru.murad.myvpn.repository.VpnTariffRepository;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentOrderCreationServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");
    private static final long TELEGRAM_ID = 100L;

    private TelegramUserRepository userRepository;
    private VpnTariffRepository tariffRepository;
    private PaymentOrderRepository orderRepository;
    private PaymentOrderCreationServiceImpl service;

    @BeforeEach
    void setUp() {
        userRepository = mock(TelegramUserRepository.class);
        tariffRepository = mock(VpnTariffRepository.class);
        orderRepository = mock(PaymentOrderRepository.class);
        service = new PaymentOrderCreationServiceImpl(
                userRepository,
                tariffRepository,
                orderRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(userRepository.findByTelegramId(TELEGRAM_ID))
                .thenReturn(Optional.of(user()));
        when(tariffRepository.findByCodeAndActiveTrue("MONTH"))
                .thenReturn(Optional.of(tariff()));
        when(orderRepository.findOpenByUser(any())).thenReturn(Optional.empty());
    }

    @Test
    void shouldCreateOrderUsingFixedClockAndTariffSnapshot() {
        when(orderRepository.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PaymentOrder order = service.create(
                TELEGRAM_ID,
                "MONTH",
                PaymentProviderType.FAKE,
                Duration.ofHours(1));

        assertThat(order.getCreatedAt()).isEqualTo(NOW);
        assertThat(order.getUpdatedAt()).isEqualTo(NOW);
        assertThat(order.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(order.getId()).isNotNull();
        assertThat(order.getIdempotenceKey()).isNotNull();
        assertThat(order.getSubscription()).isNull();
        assertThat(order.getProvider()).isEqualTo(PaymentProviderType.FAKE);
        assertThat(order.getAmount()).isEqualByComparingTo("90.00");
        assertThat(order.getTariffCodeSnapshot()).isEqualTo("MONTH");
        assertThat(order.getTariffNameSnapshot()).isEqualTo("Monthly");
        assertThat(order.getDurationDaysSnapshot()).isEqualTo(30);
    }

    @Test
    void openOrderConstraintMustBecomeOpenOrderException() {
        when(orderRepository.saveAndFlush(any())).thenThrow(integrityViolation(
                "uk_payment_order_open_user"));

        assertThatThrownBy(() -> create())
                .isInstanceOf(OpenPaymentOrderAlreadyExistsException.class)
                .hasMessage("Open payment order already exists")
                .hasMessageNotContaining("uk_payment_order_open_user")
                .hasMessageNotContaining("SQL");
    }

    @Test
    void otherUniqueConstraintMustBecomeSafePersistenceException() {
        assertSafeGenericFailure("uk_payment_orders_idempotence_key");
    }

    @Test
    void foreignKeyConstraintMustBecomeSafePersistenceException() {
        assertSafeGenericFailure("fk_payment_orders_user");
    }

    @Test
    void checkConstraintMustBecomeSafePersistenceException() {
        assertSafeGenericFailure("chk_payment_orders_amount_positive");
    }

    @Test
    void missingConstraintNameMustBecomeSafePersistenceException() {
        when(orderRepository.saveAndFlush(any())).thenThrow(
                new DataIntegrityViolationException("driver SQL details"));

        assertThatThrownBy(this::create)
                .isInstanceOf(PaymentOrderPersistenceException.class)
                .hasMessage("Payment order persistence failed")
                .hasMessageNotContaining("SQL")
                .hasMessageNotContaining("constraint")
                .hasMessageNotContaining("driver");
    }

    private void assertSafeGenericFailure(String constraintName) {
        when(orderRepository.saveAndFlush(any()))
                .thenThrow(integrityViolation(constraintName));

        assertThatThrownBy(this::create)
                .isInstanceOf(PaymentOrderPersistenceException.class)
                .isNotInstanceOf(OpenPaymentOrderAlreadyExistsException.class)
                .hasMessage("Payment order persistence failed")
                .hasMessageNotContaining(constraintName)
                .hasMessageNotContaining("SQL");
    }

    private PaymentOrder create() {
        return service.create(
                TELEGRAM_ID,
                "MONTH",
                PaymentProviderType.FAKE,
                Duration.ofHours(1));
    }

    private DataIntegrityViolationException integrityViolation(String constraintName) {
        SQLException sqlException = new SQLException("sensitive SQL driver details");
        ConstraintViolationException hibernateException =
                new ConstraintViolationException(
                        "sensitive SQL details",
                        sqlException,
                        "insert into payment_orders ...",
                        constraintName);
        return new DataIntegrityViolationException(
                "sensitive persistence details", hibernateException);
    }

    private TelegramUser user() {
        return TelegramUser.builder()
                .id(UUID.randomUUID())
                .telegramId(TELEGRAM_ID)
                .chatId(200L)
                .role(UserRole.USER)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }

    private VpnTariff tariff() {
        return VpnTariff.builder()
                .id(UUID.randomUUID())
                .code("MONTH")
                .name("Monthly")
                .durationDays(30)
                .price(new BigDecimal("90.00"))
                .currency("RUB")
                .active(true)
                .createdAt(NOW)
                .updatedAt(NOW)
                .build();
    }
}
