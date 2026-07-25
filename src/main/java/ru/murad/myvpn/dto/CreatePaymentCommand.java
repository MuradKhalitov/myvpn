package ru.murad.myvpn.dto;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Map;
import java.util.UUID;

public record CreatePaymentCommand(
        UUID paymentOrderId,
        UUID idempotenceKey,
        BigDecimal amount,
        String currency,
        String description,
        URI returnUrl,
        Map<String, String> metadata
) {
    public CreatePaymentCommand {
        metadata = Map.copyOf(metadata);
    }

    @Override
    public String toString() {
        return "CreatePaymentCommand[amount=" + amount
                + ", currency=" + currency + "]";
    }
}
