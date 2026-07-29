package ru.murad.myvpn.controller;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery;
import org.telegram.telegrambots.meta.api.methods.invoices.SendInvoice;
import org.telegram.telegrambots.meta.api.objects.payments.LabeledPrice;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import ru.murad.myvpn.config.ConditionalOnTelegramYooKassa;
import ru.murad.myvpn.config.TelegramPaymentProperties;
import ru.murad.myvpn.config.TelegramProperties;
import ru.murad.myvpn.dto.TelegramInvoiceRequest;
import ru.murad.myvpn.exception.PaymentProviderPermanentException;
import ru.murad.myvpn.exception.PaymentProviderUncertainException;
import ru.murad.myvpn.service.TelegramPaymentGateway;

@Component
@Profile("!test")
@ConditionalOnTelegramYooKassa
@Slf4j
public class TelegramBotApiPaymentGateway implements TelegramPaymentGateway {

    private final TelegramPaymentProperties properties;
    private final TelegramClient client;

    @Autowired
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
        validateInvoice(request);
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
            var response = client.execute(builder.build());
            if (response == null || response.getMessageId() == null
                || response.getMessageId() <= 0) {
                throw new PaymentProviderUncertainException(
                    "Telegram invoice response is incomplete");
            }
            return response.getMessageId();
        } catch (TelegramApiRequestException exception) {
            Integer code = exception.getErrorCode();
            logTelegramFailure(request, code, exception.getApiResponse(), exception);
            if (code != null && code >= 400 && code < 500
                && code != 408 && code != 409 && code != 429) {
                throw new PaymentProviderPermanentException(
                    "Telegram rejected the invoice");
            }
            throw new PaymentProviderUncertainException(
                "Telegram invoice delivery is uncertain");
        } catch (TelegramApiException exception) {
            logTelegramFailure(request, null, null, exception);
            throw new PaymentProviderUncertainException(
                "Telegram invoice delivery is uncertain");
        } catch (PaymentProviderUncertainException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            logTelegramFailure(request, null, null, exception);
            throw new PaymentProviderUncertainException(
                "Telegram invoice delivery is uncertain");
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

    private void validateInvoice(TelegramInvoiceRequest request) {
        if (request == null) {
            throw new PaymentProviderPermanentException(
                "Telegram invoice request is missing");
        }
        if (request.chatId() == 0) {
            throw new PaymentProviderPermanentException(
                "Telegram invoice chat is invalid");
        }
        requireLength(request.title(), 1, 32, "title");
        requireLength(request.description(), 1, 255, "description");
        int payloadBytes = request.payload() == null ? 0
            : request.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (payloadBytes < 1 || payloadBytes > 128) {
            throw new PaymentProviderPermanentException(
                "Telegram invoice payload is invalid");
        }
        if (!"RUB".equals(request.currency())) {
            throw new PaymentProviderPermanentException(
                "Telegram invoice currency must be RUB");
        }
        if (request.amountMinor() <= 0 || request.amountMinor() > Integer.MAX_VALUE) {
            throw new PaymentProviderPermanentException(
                "Telegram invoice amount is invalid");
        }
        if (properties.providerToken() == null
            || properties.providerToken().isBlank()) {
            throw new PaymentProviderPermanentException(
                "Telegram payment provider is not configured");
        }
    }

    private void requireLength(
        String value,
        int minimum,
        int maximum,
        String field
    ) {
        int length = value == null ? 0 : value.length();
        if (length < minimum || length > maximum) {
            throw new PaymentProviderPermanentException(
                "Telegram invoice " + field + " is invalid");
        }
    }

    private void logTelegramFailure(
        TelegramInvoiceRequest request,
        Integer errorCode,
        String description,
        Exception exception
    ) {
        log.error(
            "Telegram sendInvoice failed httpStatus={} telegramErrorCode={} "
                + "description={} exceptionType={} stackTrace={}",
            errorCode, errorCode, safeDescription(description, request),
            exception.getClass().getName(),
            java.util.Arrays.toString(exception.getStackTrace()));
    }

    private String safeDescription(
        String description,
        TelegramInvoiceRequest request
    ) {
        if (description == null || description.isBlank()) {
            return "unavailable";
        }
        String safe = description
            .replace(properties.providerToken(), "[REDACTED]")
            .replace(request.payload(), "[REDACTED]")
            .replaceAll("[\\r\\n\\t]", " ");
        return safe.substring(0, Math.min(safe.length(), 256));
    }
}
