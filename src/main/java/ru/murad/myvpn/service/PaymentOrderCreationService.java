package ru.murad.myvpn.service;

import ru.murad.myvpn.model.PaymentOrder;
import ru.murad.myvpn.model.PaymentProviderType;

import java.time.Duration;

public interface PaymentOrderCreationService {

    PaymentOrder create(
            long userTelegramId,
            String tariffCode,
            PaymentProviderType provider,
            Duration pendingTtl
    );
}
