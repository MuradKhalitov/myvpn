package ru.murad.myvpn.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.murad.myvpn.dto.TelegramPreCheckoutCommand;
import ru.murad.myvpn.dto.TelegramSuccessfulPaymentCommand;
import ru.murad.myvpn.service.TelegramPaymentEventService;
import ru.murad.myvpn.service.TelegramPaymentGateway;
import ru.murad.myvpn.config.ConditionalOnTelegramYooKassa;
import ru.murad.myvpn.service.TelegramPaymentTransactionService;

@Service
@RequiredArgsConstructor
@ConditionalOnTelegramYooKassa
public class TelegramPaymentEventServiceImpl implements TelegramPaymentEventService {

    private static final String SAFE_ERROR =
            "Платёж не прошёл проверку. Вернитесь в бот и создайте счёт заново.";

    private final TelegramPaymentTransactionService transactions;
    private final TelegramPaymentGateway gateway;

    @Override
    public void handlePreCheckout(TelegramPreCheckoutCommand command) {
        boolean accepted;
        try {
            accepted = transactions.validatePreCheckout(command);
        } catch (RuntimeException exception) {
            accepted = false;
        }
        gateway.answerPreCheckoutQuery(
                command.queryId(), accepted, accepted ? null : SAFE_ERROR);
    }

    @Override
    public void handleSuccessfulPayment(TelegramSuccessfulPaymentCommand command) {
        transactions.applySuccessfulPayment(command);
    }
}
