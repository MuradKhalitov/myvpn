package ru.murad.myvpn.service;

import java.util.UUID;
import ru.murad.myvpn.dto.PaymentCheckoutResult;
import ru.murad.myvpn.dto.ProviderPayment;

public interface PaymentCheckoutService {
    PaymentCheckoutResult startCheckout(UUID accountId, String tariffCode);
    ProviderPayment checkCurrentPayment(UUID accountId);
}
