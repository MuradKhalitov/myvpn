package ru.murad.myvpn.mapper;

import org.mapstruct.Mapper;
import ru.murad.myvpn.dto.VpnTariffDto;
import ru.murad.myvpn.model.VpnTariff;

@Mapper(componentModel = "spring")
public interface VpnTariffMapper {

    VpnTariffDto toDto(VpnTariff tariff);
}
