package ru.murad.myvpn.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.murad.myvpn.exception.OpenPaymentOrderAlreadyExistsException;
import ru.murad.myvpn.exception.PaymentOrderPersistenceException;
import ru.murad.myvpn.exception.TelegramUserNotFoundException;
import ru.murad.myvpn.exception.VpnTariffNotFoundException;
import ru.murad.myvpn.model.PaymentOrder;
import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.model.TelegramUser;
import ru.murad.myvpn.model.VpnTariff;
import ru.murad.myvpn.repository.PaymentOrderRepository;
import ru.murad.myvpn.repository.DatabaseConstraintExtractor;
import ru.murad.myvpn.repository.TelegramUserRepository;
import ru.murad.myvpn.repository.VpnTariffRepository;
import ru.murad.myvpn.service.PaymentOrderCreationService;

import java.time.Clock;
import java.time.Duration;

@Service
@RequiredArgsConstructor
public class PaymentOrderCreationServiceImpl
        implements PaymentOrderCreationService {

    private static final String OPEN_ORDER_CONSTRAINT =
            "uk_payment_order_open_user";
    private final TelegramUserRepository userRepository;
    private final VpnTariffRepository tariffRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final Clock clock;

    @Override
    @Transactional
    public PaymentOrder create(
            long userTelegramId,
            String tariffCode,
            PaymentProviderType provider,
            Duration pendingTtl
    ) {
        TelegramUser user = userRepository.findByTelegramId(userTelegramId)
                .orElseThrow(() -> new TelegramUserNotFoundException(userTelegramId));
        VpnTariff tariff = tariffRepository.findByCodeAndActiveTrue(tariffCode)
                .orElseThrow(() -> new VpnTariffNotFoundException(tariffCode));
        if (paymentOrderRepository.findOpenByUser(user.getId()).isPresent()) {
            throw new OpenPaymentOrderAlreadyExistsException();
        }

        PaymentOrder order = PaymentOrder.create(
                user, tariff, provider, clock.instant(), pendingTtl);
        try {
            return paymentOrderRepository.saveAndFlush(order);
        } catch (DataIntegrityViolationException exception) {
            if (DatabaseConstraintExtractor.extract(exception)
                    .filter(OPEN_ORDER_CONSTRAINT::equals)
                    .isPresent()) {
                throw new OpenPaymentOrderAlreadyExistsException();
            }
            throw new PaymentOrderPersistenceException();
        }
    }
}
