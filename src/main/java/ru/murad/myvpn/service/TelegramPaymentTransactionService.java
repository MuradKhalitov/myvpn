package ru.murad.myvpn.service;

import ru.murad.myvpn.dto.TelegramPreCheckoutCommand;
import ru.murad.myvpn.dto.TelegramSuccessfulPaymentCommand;

public interface TelegramPaymentTransactionService {

    boolean validatePreCheckout(TelegramPreCheckoutCommand command);

    void applySuccessfulPayment(TelegramSuccessfulPaymentCommand command);
}
