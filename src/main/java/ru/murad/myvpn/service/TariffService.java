package ru.murad.myvpn.service;

import ru.murad.myvpn.dto.VpnTariffDto;

import java.util.List;

public interface TariffService {

    List<VpnTariffDto> findAvailableTariffs();
}
