package ru.murad.myvpn.service;

import ru.murad.myvpn.dto.CreatedPayment;
import ru.murad.myvpn.dto.PaymentCheckoutResult;
import ru.murad.myvpn.dto.PreparedCheckout;
import ru.murad.myvpn.model.PaymentProviderType;

import java.time.Duration;
import java.time.Instant;

public interface PaymentCheckoutTransactionService {

    PreparedCheckout prepareCheckout(
            long telegramUserId,
            String tariffCode,
            PaymentProviderType provider,
            Duration pendingTtl,
            Instant now
    );

    PaymentCheckoutResult applyCreatedPayment(
            PreparedCheckout prepared,
            CreatedPayment created,
            Instant now
    );

    PaymentCheckoutResult applyTelegramInvoice(
            PreparedCheckout prepared, int messageId, Instant now);

    void markPermanentFailure(PreparedCheckout prepared, Instant now);

    void markTelegramInvoiceUncertain(PreparedCheckout prepared, Instant now);
}
