package ru.murad.myvpn.controller;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.ApiResponse;
import org.telegram.telegrambots.meta.api.objects.ResponseParameters;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import ru.murad.myvpn.exception.TelegramDeliveryPermanentException;
import ru.murad.myvpn.exception.TelegramDeliveryTransientException;
import ru.murad.myvpn.model.VpnDeliveryFailureCode;
import java.io.IOException;
import static org.assertj.core.api.Assertions.*;

class TelegramVpnConfigurationDeliveryGatewayTest {
    @Test void rateLimitUsesOfficialRetryAfterWithoutLeakingRawResponse() {
        RuntimeException mapped = TelegramVpnConfigurationDeliveryGateway.mapped(request(429, "Too Many Requests", 17));
        assertThat(mapped).isInstanceOf(TelegramDeliveryTransientException.class);
        TelegramDeliveryTransientException transientFailure = (TelegramDeliveryTransientException) mapped;
        assertThat(transientFailure.retryAfter()).hasSeconds(17);
        assertThat(transientFailure.safeFailureCode()).isEqualTo(VpnDeliveryFailureCode.TELEGRAM_RATE_LIMITED);
        assertThat(transientFailure.toString()).doesNotContain("Too Many Requests", "raw-response");
    }
    @Test void rateLimitWithoutParameterUsesGenericTransientBackoff() {
        TelegramDeliveryTransientException mapped = (TelegramDeliveryTransientException) TelegramVpnConfigurationDeliveryGateway.mapped(request(429, "raw-response", null));
        assertThat(mapped.retryAfter()).isNull(); assertThat(mapped.safeFailureCode()).isEqualTo(VpnDeliveryFailureCode.TELEGRAM_RATE_LIMITED);
    }
    @Test void serverErrorsAndIoAreTransient() {
        assertThat(TelegramVpnConfigurationDeliveryGateway.mapped(request(503, "raw-response", null))).isInstanceOf(TelegramDeliveryTransientException.class);
        assertThat(TelegramVpnConfigurationDeliveryGateway.mapped(new TelegramApiException(new IOException("connection reset raw-response")))).isInstanceOf(TelegramDeliveryTransientException.class);
    }
    @Test void knownRejectionsArePermanentAndUnknown400IsConservativePermanent() {
        assertCode(request(400, "bot was blocked by the user", null), VpnDeliveryFailureCode.TELEGRAM_BOT_BLOCKED);
        assertCode(request(400, "chat not found", null), VpnDeliveryFailureCode.TELEGRAM_CHAT_NOT_FOUND);
        assertCode(request(400, "message is too long", null), VpnDeliveryFailureCode.TELEGRAM_MESSAGE_TOO_LONG);
        assertCode(request(400, "raw-response", null), VpnDeliveryFailureCode.TELEGRAM_REJECTED);
    }
    private void assertCode(TelegramApiException input, VpnDeliveryFailureCode expected) {
        RuntimeException mapped = TelegramVpnConfigurationDeliveryGateway.mapped(input);
        assertThat(mapped).isInstanceOf(TelegramDeliveryPermanentException.class);
        assertThat(((TelegramDeliveryPermanentException) mapped).safeFailureCode()).isEqualTo(expected);
        assertThat(mapped.toString()).doesNotContain("raw-response");
    }
    private TelegramApiRequestException request(int status, String message, Integer retryAfter) {
        return new TelegramApiRequestException(message, new ApiResponse<>(false, status, "raw-response", new ResponseParameters(null, retryAfter), null));
    }
}
