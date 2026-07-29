package ru.murad.myvpn.service;

import ru.murad.myvpn.dto.TelegramInvoiceRequest;

public interface TelegramPaymentGateway {

    int sendInvoice(TelegramInvoiceRequest request);

    void answerPreCheckoutQuery(String queryId, boolean ok, String errorMessage);
}
