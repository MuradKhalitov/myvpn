package ru.murad.myvpn.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery;
import org.telegram.telegrambots.meta.api.methods.invoices.SendInvoice;
import org.telegram.telegrambots.meta.api.objects.payments.LabeledPrice;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import ru.murad.myvpn.config.TelegramPaymentProperties;
import ru.murad.myvpn.config.TelegramProperties;
import ru.murad.myvpn.dto.TelegramInvoiceRequest;
import ru.murad.myvpn.exception.PaymentProviderPermanentException;
import ru.murad.myvpn.exception.PaymentProviderUncertainException;
import ru.murad.myvpn.service.TelegramPaymentGateway;

import java.util.List;

@Component
@Profile("!test")
@ConditionalOnProperty(name = "payment.provider", havingValue = "telegram-yookassa")
public class TelegramBotApiPaymentGateway implements TelegramPaymentGateway {

    private final TelegramPaymentProperties properties;
    private final TelegramClient client;

    public TelegramBotApiPaymentGateway(
            TelegramProperties telegramProperties,
            TelegramPaymentProperties paymentProperties
    ) {
        this(paymentProperties, new OkHttpTelegramClient(telegramProperties.botToken()));
    }

    TelegramBotApiPaymentGateway(
            TelegramPaymentProperties properties, TelegramClient client
    ) {
        this.properties = properties;
        this.client = client;
    }

    @Override
    public int sendInvoice(TelegramInvoiceRequest request) {
        if (request.amountMinor() <= 0 || request.amountMinor() > Integer.MAX_VALUE) {
            throw new PaymentProviderPermanentException("Telegram invoice amount is invalid");
        }
        var builder = SendInvoice.builder()
                .chatId(request.chatId())
                .title(request.title())
                .description(request.description())
                .payload(request.payload())
                .providerToken(properties.providerToken())
                .currency(request.currency())
                .prices(List.of(new LabeledPrice(
                        request.title(), Math.toIntExact(request.amountMinor()))));
        if (properties.receiptEnabled()) {
            boolean email = "email".equalsIgnoreCase(properties.receiptContact());
            builder.needEmail(email).sendEmailToProvider(email)
                    .needPhoneNumber(!email).sendPhoneNumberToProvider(!email)
                    .providerData(receiptProviderData(request));
        }
        try {
            return client.execute(builder.build()).getMessageId();
        } catch (TelegramApiRequestException exception) {
            Integer code = exception.getErrorCode();
            if (code != null && code >= 400 && code < 500 && code != 429) {
                throw new PaymentProviderPermanentException(
                        "Telegram rejected the invoice");
            }
            throw new PaymentProviderUncertainException(
                    "Telegram invoice delivery is uncertain");
        } catch (TelegramApiException exception) {
            throw new PaymentProviderUncertainException(
                    "Telegram invoice delivery is uncertain");
        } catch (RuntimeException exception) {
            throw new PaymentProviderPermanentException(
                    "Telegram invoice request is invalid");
        }
    }

    @Override
    public void answerPreCheckoutQuery(
            String queryId, boolean ok, String errorMessage
    ) {
        try {
            client.execute(AnswerPreCheckoutQuery.builder()
                    .preCheckoutQueryId(queryId)
                    .ok(ok)
                    .errorMessage(ok ? null : errorMessage)
                    .build());
        } catch (TelegramApiException exception) {
            throw new PaymentProviderUncertainException(
                    "Telegram pre-checkout response failed");
        }
    }

    private String receiptProviderData(TelegramInvoiceRequest request) {
        String safeDescription = request.description()
                .replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"receipt\":{\"items\":[{\"description\":\"" + safeDescription
                + "\",\"quantity\":\"1.00\",\"amount\":{\"value\":\""
                + java.math.BigDecimal.valueOf(request.amountMinor(), 2).toPlainString()
                + "\",\"currency\":\"RUB\"},\"vat_code\":"
                + properties.vatCode()
                + ",\"payment_mode\":\"full_payment\",\"payment_subject\":\"service\"}]}}";
    }
}
