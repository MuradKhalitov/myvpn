package ru.murad.myvpn.service.impl;

import jakarta.persistence.EntityManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
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
@ConditionalOnProperty(name = "payment.provider", havingValue = "fake")
public class FakePaymentRecoveryServiceImpl implements FakePaymentRecoveryService {

    private final AdminAuthorizationService adminAuthorizationService;
    private final TelegramUserRepository userRepository;
    private final PaymentOrderRepository orderRepository;
    private final Clock clock;
    private final FakePaymentProvider fakePaymentProvider;
    private final EntityManager entityManager;

    @Autowired
    public FakePaymentRecoveryServiceImpl(
            AdminAuthorizationService adminAuthorizationService,
            TelegramUserRepository userRepository,
            PaymentOrderRepository orderRepository,
            Clock clock,
            FakePaymentProvider fakePaymentProvider,
            EntityManager entityManager
    ) {
        this.adminAuthorizationService = adminAuthorizationService;
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.clock = clock;
        this.fakePaymentProvider = fakePaymentProvider;
        this.entityManager = entityManager;
    }

    public FakePaymentRecoveryServiceImpl(
            AdminAuthorizationService adminAuthorizationService,
            TelegramUserRepository userRepository,
            PaymentOrderRepository orderRepository,
            Clock clock,
            FakePaymentProvider fakePaymentProvider
    ) {
        this(adminAuthorizationService, userRepository, orderRepository, clock,
                fakePaymentProvider, null);
    }

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
                        || candidate.getStatus() == PaymentStatus.CREATING
                        || (candidate.getStatus() == PaymentStatus.MANUAL_REVIEW_REQUIRED
                        && "PROVIDER_PAYMENT_NOT_FOUND".equals(candidate.getSafeFailureCode())))
                .filter(candidate -> candidate.getProviderPaymentId() != null
                        && !candidate.getProviderPaymentId().isBlank())
                .orElseThrow(PaymentNotFoundException::new);
        try {
            fakePaymentProvider.getPayment(order.getProviderPaymentId());
            throw new FakePaymentStillExistsException();
        } catch (PaymentNotFoundException stateLost) {
            if (entityManager != null) {
                entityManager.clear();
            }
            var locked = orderRepository.findByIdForUpdate(order.getId())
                    .orElseThrow(PaymentNotFoundException::new);
            if (locked.getProvider() != PaymentProviderType.FAKE
                    || (locked.getStatus() != PaymentStatus.PENDING
                    && locked.getStatus() != PaymentStatus.CREATING
                    && !(locked.getStatus() == PaymentStatus.MANUAL_REVIEW_REQUIRED
                    && "PROVIDER_PAYMENT_NOT_FOUND".equals(locked.getSafeFailureCode())))
                    || !order.getProviderPaymentId().equals(
                    locked.getProviderPaymentId())) {
                throw new PaymentNotFoundException();
            }
            locked.markFailed("FAKE_PROVIDER_STATE_LOST", clock.instant());
            orderRepository.saveAndFlush(locked);
        }
    }
}
