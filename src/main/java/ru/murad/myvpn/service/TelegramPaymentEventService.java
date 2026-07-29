package ru.murad.myvpn.service;

import ru.murad.myvpn.dto.TelegramPreCheckoutCommand;
import ru.murad.myvpn.dto.TelegramSuccessfulPaymentCommand;

public interface TelegramPaymentEventService {

    void handlePreCheckout(TelegramPreCheckoutCommand command);

    void handleSuccessfulPayment(TelegramSuccessfulPaymentCommand command);
}
