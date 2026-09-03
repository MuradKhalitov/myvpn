package ru.murad.myvpn.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record TariffResponse(
        UUID id,
        String code,
        String name,
        String description,
        int durationDays,
        BigDecimal price,
        String currency
) {
    public static TariffResponse from(VpnTariffDto tariff) {
        return new TariffResponse(tariff.id(), tariff.code(), tariff.name(), tariff.description(),
                tariff.durationDays(), tariff.price(), tariff.currency());
    }
}
