package ru.murad.myvpn.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.murad.myvpn.client.FakePaymentProvider;
import ru.murad.myvpn.service.FakePaymentControlService;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.provider", havingValue = "fake",
        matchIfMissing = true)
public class FakePaymentControlServiceImpl implements FakePaymentControlService {

    private final FakePaymentProvider provider;

    @Override
    public void markSucceeded(String providerPaymentId) {
        provider.markSucceeded(providerPaymentId);
    }

    @Override
    public void markCanceled(String providerPaymentId) {
        provider.markCanceled(providerPaymentId);
    }
}
