package ru.murad.myvpn.service;

import ru.murad.myvpn.dto.PaymentCheckoutResult;
import ru.murad.myvpn.dto.ProviderPayment;

public interface PaymentCheckoutService {

    PaymentCheckoutResult startCheckout(long telegramUserId, String tariffCode);

    ProviderPayment checkCurrentPayment(long telegramUserId);
}
