package ru.murad.myvpn.service;

import java.time.Duration;
import java.util.UUID;
import ru.murad.myvpn.model.PaymentOrder;
import ru.murad.myvpn.model.PaymentProviderType;

public interface PaymentOrderCreationService {
    PaymentOrder create(UUID accountId, String tariffCode, PaymentProviderType provider, Duration pendingTtl);
}
