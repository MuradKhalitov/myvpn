package ru.murad.myvpn.service.impl;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import ru.murad.myvpn.exception.OpenPaymentOrderAlreadyExistsException;
import ru.murad.myvpn.model.Account;
import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.model.VpnTariff;
import ru.murad.myvpn.repository.AccountRepository;
import ru.murad.myvpn.repository.PaymentOrderRepository;
import ru.murad.myvpn.repository.VpnTariffRepository;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PaymentOrderCreationServiceImplTest {

    @Test
    void mapsActualOpenOrderPartialIndexRaceToDomainConflict() {
        AccountRepository accounts = mock(AccountRepository.class);
        VpnTariffRepository tariffs = mock(VpnTariffRepository.class);
        PaymentOrderRepository orders = mock(PaymentOrderRepository.class);
        UUID accountId = UUID.randomUUID();
        Account account = mock(Account.class);
        VpnTariff tariff = mock(VpnTariff.class);
        when(accounts.findById(accountId)).thenReturn(Optional.of(account));
        when(tariffs.findByCodeAndActiveTrue("MONTH_1")).thenReturn(Optional.of(tariff));
        when(orders.findOpenByAccount(accountId)).thenReturn(Optional.empty());
        when(tariff.getPrice()).thenReturn(new java.math.BigDecimal("100.00"));
        when(tariff.getCurrency()).thenReturn("RUB");
        when(tariff.getCode()).thenReturn("MONTH_1");
        when(tariff.getName()).thenReturn("Month");
        when(tariff.getDurationDays()).thenReturn(30);
        var violation = new ConstraintViolationException("duplicate", new SQLException("duplicate"),
                "insert", "uk_payment_order_open_account");
        when(orders.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate", violation));

        var service = new PaymentOrderCreationServiceImpl(accounts, tariffs, orders,
                Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> service.create(accountId, "MONTH_1", PaymentProviderType.FAKE, Duration.ofHours(1)))
                .isInstanceOf(OpenPaymentOrderAlreadyExistsException.class);
    }
}
