package ru.murad.myvpn.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import ru.murad.myvpn.dto.TelegramPreCheckoutCommand;
import ru.murad.myvpn.dto.TelegramSuccessfulPaymentCommand;
import ru.murad.myvpn.service.TelegramPaymentEventService;
import ru.murad.myvpn.service.TelegramPaymentGateway;
import ru.murad.myvpn.service.TelegramPaymentTransactionService;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.provider", havingValue = "telegram-yookassa")
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
