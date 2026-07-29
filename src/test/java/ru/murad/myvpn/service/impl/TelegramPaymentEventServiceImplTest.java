package ru.murad.myvpn.service.impl;

import org.junit.jupiter.api.Test;
import ru.murad.myvpn.dto.TelegramPreCheckoutCommand;
import ru.murad.myvpn.service.TelegramPaymentGateway;
import ru.murad.myvpn.service.TelegramPaymentTransactionService;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramPaymentEventServiceImplTest {

    private final TelegramPaymentTransactionService transactions =
            mock(TelegramPaymentTransactionService.class);
    private final TelegramPaymentGateway gateway = mock(TelegramPaymentGateway.class);
    private final TelegramPaymentEventServiceImpl service =
            new TelegramPaymentEventServiceImpl(transactions, gateway);
    private final TelegramPreCheckoutCommand command = new TelegramPreCheckoutCommand(
            "query", 100, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "RUB", 14990);

    @Test
    void answersTrueForValidQuery() {
        when(transactions.validatePreCheckout(command)).thenReturn(true);
        service.handlePreCheckout(command);
        verify(gateway).answerPreCheckoutQuery("query", true, null);
    }

    @Test
    void answersFalseForRejectedQuery() {
        service.handlePreCheckout(command);
        verify(gateway).answerPreCheckoutQuery(
                org.mockito.ArgumentMatchers.eq("query"),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.contains("проверку"));
    }

    @Test
    void answersFalseWhenValidationFailsWithoutActivationCalls() {
        when(transactions.validatePreCheckout(command))
                .thenThrow(new IllegalStateException("db"));
        service.handlePreCheckout(command);
        verify(gateway).answerPreCheckoutQuery(
                org.mockito.ArgumentMatchers.eq("query"),
                org.mockito.ArgumentMatchers.eq(false),
                org.mockito.ArgumentMatchers.anyString());
    }
}
