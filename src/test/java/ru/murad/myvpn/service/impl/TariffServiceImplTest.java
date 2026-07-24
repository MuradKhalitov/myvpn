package ru.murad.myvpn.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.murad.myvpn.dto.VpnTariffDto;
import ru.murad.myvpn.mapper.VpnTariffMapper;
import ru.murad.myvpn.model.VpnTariff;
import ru.murad.myvpn.repository.VpnTariffRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TariffServiceImplTest {

    @Mock
    private VpnTariffRepository tariffRepository;

    @Mock
    private VpnTariffMapper tariffMapper;

    @Test
    void shouldReturnMappedAvailableTariffsInRepositoryOrder() {
        VpnTariff monthly = VpnTariff.builder().code("MONTH").build();
        VpnTariff yearly = VpnTariff.builder().code("YEAR").build();
        VpnTariffDto monthlyDto = dto("MONTH", 30, "300.00");
        VpnTariffDto yearlyDto = dto("YEAR", 365, "3000.00");
        when(tariffRepository.findAllByActiveTrueOrderByPriceAscDurationDaysAsc())
                .thenReturn(List.of(monthly, yearly));
        when(tariffMapper.toDto(monthly)).thenReturn(monthlyDto);
        when(tariffMapper.toDto(yearly)).thenReturn(yearlyDto);
        TariffServiceImpl service = new TariffServiceImpl(tariffRepository, tariffMapper);

        List<VpnTariffDto> result = service.findAvailableTariffs();

        assertThat(result).containsExactly(monthlyDto, yearlyDto);
        InOrder mapperOrder = inOrder(tariffMapper);
        mapperOrder.verify(tariffMapper).toDto(monthly);
        mapperOrder.verify(tariffMapper).toDto(yearly);
    }

    @Test
    void shouldReturnEmptyListWhenNoTariffsAreAvailable() {
        when(tariffRepository.findAllByActiveTrueOrderByPriceAscDurationDaysAsc())
                .thenReturn(List.of());
        TariffServiceImpl service = new TariffServiceImpl(tariffRepository, tariffMapper);

        List<VpnTariffDto> result = service.findAvailableTariffs();

        assertThat(result).isEmpty();
    }

    private VpnTariffDto dto(String code, int durationDays, String price) {
        return new VpnTariffDto(
                UUID.randomUUID(),
                code,
                code + " tariff",
                "Test tariff",
                durationDays,
                new BigDecimal(price),
                "RUB"
        );
    }
}
