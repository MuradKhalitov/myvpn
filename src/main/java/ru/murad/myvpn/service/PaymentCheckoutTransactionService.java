package ru.murad.myvpn.service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import ru.murad.myvpn.dto.CreatedPayment;
import ru.murad.myvpn.dto.PaymentCheckoutResult;
import ru.murad.myvpn.dto.PreparedCheckout;
import ru.murad.myvpn.model.PaymentProviderType;

public interface PaymentCheckoutTransactionService {
    PreparedCheckout prepareCheckout(UUID accountId, String tariffCode, PaymentProviderType provider, Duration pendingTtl, Instant now);
    PaymentCheckoutResult applyCreatedPayment(PreparedCheckout prepared, CreatedPayment created, Instant now);
    void markPermanentFailure(PreparedCheckout prepared, Instant now);
}
