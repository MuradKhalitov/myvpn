package ru.murad.myvpn.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ru.murad.myvpn.dto.PreparedCheckout;
import ru.murad.myvpn.dto.TelegramInvoiceRequest;
import ru.murad.myvpn.exception.PaymentProviderPermanentException;

import java.math.RoundingMode;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.provider", havingValue = "telegram-yookassa")
public class TelegramInvoiceProvider {

    private final TelegramPaymentGateway gateway;

    public int sendInvoice(PreparedCheckout checkout) {
        if (!"RUB".equals(checkout.currency())) {
            throw new PaymentProviderPermanentException("Telegram invoice currency must be RUB");
        }
        long minor;
        try {
            minor = checkout.amount().setScale(2, RoundingMode.UNNECESSARY)
                    .movePointRight(2).longValueExact();
        } catch (ArithmeticException exception) {
            throw new PaymentProviderPermanentException("Telegram invoice amount is invalid");
        }
        if (minor <= 0 || minor > Integer.MAX_VALUE) {
            throw new PaymentProviderPermanentException("Telegram invoice amount is invalid");
        }
        return gateway.sendInvoice(new TelegramInvoiceRequest(
                checkout.chatId(),
                "MyVPN — " + checkout.tariffName(),
                "VPN-подписка «" + checkout.tariffName()
                        + "» на " + checkout.durationDays() + " дней",
                checkout.telegramInvoicePayload(),
                checkout.currency(),
                minor));
    }
}
