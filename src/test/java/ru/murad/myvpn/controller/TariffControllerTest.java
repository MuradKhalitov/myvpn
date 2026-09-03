package ru.murad.myvpn.controller;

import org.junit.jupiter.api.Test;
import ru.murad.myvpn.dto.VpnTariffDto;
import ru.murad.myvpn.service.TariffService;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TariffControllerTest {
    @Test
    void returnsAvailableTariffsFromService() {
        TariffService service = mock(TariffService.class);
        var tariffs = List.of(new VpnTariffDto(UUID.randomUUID(), "MONTH_1", "Month", "desc", 30,
                new BigDecimal("100.00"), "RUB"));
        when(service.findAvailableTariffs()).thenReturn(tariffs);

        var response = new TariffController(service).list().block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).singleElement()
                .extracting(tariff -> tariff.code()).isEqualTo("MONTH_1");
        verify(service).findAvailableTariffs();
    }
}
