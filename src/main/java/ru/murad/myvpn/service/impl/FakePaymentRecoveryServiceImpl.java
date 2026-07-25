package ru.murad.myvpn.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.murad.myvpn.client.FakePaymentProvider;
import ru.murad.myvpn.exception.FakePaymentStillExistsException;
import ru.murad.myvpn.exception.PaymentNotFoundException;
import ru.murad.myvpn.model.PaymentProviderType;
import ru.murad.myvpn.model.PaymentStatus;
import ru.murad.myvpn.repository.PaymentOrderRepository;
import ru.murad.myvpn.repository.TelegramUserRepository;
import ru.murad.myvpn.service.AdminAuthorizationService;
import ru.murad.myvpn.service.FakePaymentRecoveryService;

import java.time.Clock;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.provider", havingValue = "fake")
public class FakePaymentRecoveryServiceImpl implements FakePaymentRecoveryService {

    private final AdminAuthorizationService adminAuthorizationService;
    private final TelegramUserRepository userRepository;
    private final PaymentOrderRepository orderRepository;
    private final Clock clock;
    private final FakePaymentProvider fakePaymentProvider;

    @Override
    @Transactional
    public void resetLostPayment(
            long administratorId,
            long targetTelegramId
    ) {
        adminAuthorizationService.checkAccess(administratorId);
        var user = userRepository.findByTelegramId(targetTelegramId)
                .orElseThrow(PaymentNotFoundException::new);
        var order = orderRepository.findOpenByUser(user.getId())
                .filter(candidate -> candidate.getProvider() == PaymentProviderType.FAKE)
                .filter(candidate -> candidate.getStatus() == PaymentStatus.PENDING
                        || candidate.getStatus() == PaymentStatus.CREATING)
                .filter(candidate -> candidate.getProviderPaymentId() != null
                        && !candidate.getProviderPaymentId().isBlank())
                .orElseThrow(PaymentNotFoundException::new);
        try {
            fakePaymentProvider.getPayment(order.getProviderPaymentId());
            throw new FakePaymentStillExistsException();
        } catch (PaymentNotFoundException stateLost) {
            var locked = orderRepository.findByIdForUpdate(order.getId())
                    .orElseThrow(PaymentNotFoundException::new);
            if (locked.getProvider() != PaymentProviderType.FAKE
                    || (locked.getStatus() != PaymentStatus.PENDING
                    && locked.getStatus() != PaymentStatus.CREATING)
                    || !order.getProviderPaymentId().equals(
                    locked.getProviderPaymentId())) {
                throw new PaymentNotFoundException();
            }
            locked.markFailed("FAKE_PROVIDER_STATE_LOST", clock.instant());
            orderRepository.saveAndFlush(locked);
        }
    }
}
