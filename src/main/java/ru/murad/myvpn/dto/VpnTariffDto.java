package ru.murad.myvpn.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record VpnTariffDto(
        UUID id,
        String code,
        String name,
        String description,
        int durationDays,
        BigDecimal price,
        String currency
) {
}
