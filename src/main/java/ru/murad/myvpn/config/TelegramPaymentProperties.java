package ru.murad.myvpn.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "payment.telegram")
public record TelegramPaymentProperties(
        String providerToken,
        String currency,
        Duration preCheckoutTimeout,
        boolean receiptEnabled,
        String vatCode,
        String receiptContact
) {
    public TelegramPaymentProperties {
        if (currency == null || !"RUB".equals(currency)) {
            throw new IllegalStateException("Telegram payment currency must be RUB");
        }
        if (preCheckoutTimeout == null || preCheckoutTimeout.isZero()
                || preCheckoutTimeout.isNegative()
                || preCheckoutTimeout.compareTo(Duration.ofSeconds(9)) > 0) {
            throw new IllegalStateException("Telegram pre-checkout timeout must be at most 9 seconds");
        }
        if (receiptEnabled && (vatCode == null || vatCode.isBlank())) {
            throw new IllegalStateException(
                    "Telegram payment VAT code is required when receipt is enabled");
        }
        if (receiptEnabled) {
            try {
                int code = Integer.parseInt(vatCode);
                if (code < 1 || code > 12) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException invalid) {
                throw new IllegalStateException(
                        "Telegram payment VAT code must be between 1 and 12");
            }
        }
        if (receiptEnabled && (receiptContact == null
                || (!"email".equalsIgnoreCase(receiptContact)
                && !"phone".equalsIgnoreCase(receiptContact)))) {
            throw new IllegalStateException(
                    "Telegram payment receipt contact is required when receipt is enabled");
        }
    }

    @Override
    public String toString() {
        return "TelegramPaymentProperties[currency=" + currency
                + ", preCheckoutTimeout=" + preCheckoutTimeout
                + ", receiptEnabled=" + receiptEnabled
                + ", providerTokenRedacted=true]";
    }
}
