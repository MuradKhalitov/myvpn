package ru.murad.myvpn.service.impl;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.murad.myvpn.exception.OpenPaymentOrderAlreadyExistsException;
import ru.murad.myvpn.exception.PaymentOrderPersistenceException;
import ru.murad.myvpn.exception.VpnTariffNotFoundException;
import ru.murad.myvpn.model.PaymentOrder;
import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.repository.AccountRepository;
import ru.murad.myvpn.repository.DatabaseConstraintExtractor;
import ru.murad.myvpn.repository.PaymentOrderRepository;
import ru.murad.myvpn.repository.VpnTariffRepository;
import ru.murad.myvpn.service.PaymentOrderCreationService;

@Service @RequiredArgsConstructor
public class PaymentOrderCreationServiceImpl implements PaymentOrderCreationService {
    private static final String OPEN_ORDER_CONSTRAINT = "uk_payment_order_open_user";
    private final AccountRepository accountRepository;
    private final VpnTariffRepository tariffRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final Clock clock;
    @Override @Transactional
    public PaymentOrder create(UUID accountId, String tariffCode, PaymentProviderType provider, Duration pendingTtl) {
        var account = accountRepository.findById(accountId).orElseThrow();
        var tariff = tariffRepository.findByCodeAndActiveTrue(tariffCode).orElseThrow(() -> new VpnTariffNotFoundException(tariffCode));
        if (paymentOrderRepository.findOpenByAccount(accountId).isPresent()) throw new OpenPaymentOrderAlreadyExistsException();
        try { return paymentOrderRepository.saveAndFlush(PaymentOrder.create(account, tariff, provider, clock.instant(), pendingTtl)); }
        catch (DataIntegrityViolationException exception) {
            if (DatabaseConstraintExtractor.extract(exception).filter(OPEN_ORDER_CONSTRAINT::equals).isPresent()) throw new OpenPaymentOrderAlreadyExistsException();
            throw new PaymentOrderPersistenceException();
        }
    }
}
