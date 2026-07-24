package ru.murad.myvpn.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.murad.myvpn.dto.VpnTariffDto;
import ru.murad.myvpn.mapper.VpnTariffMapper;
import ru.murad.myvpn.repository.VpnTariffRepository;
import ru.murad.myvpn.service.TariffService;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TariffServiceImpl implements TariffService {

    private final VpnTariffRepository tariffRepository;
    private final VpnTariffMapper tariffMapper;

    @Override
    @Transactional(readOnly = true)
    public List<VpnTariffDto> findAvailableTariffs() {
        return tariffRepository.findAllByActiveTrueOrderByPriceAscDurationDaysAsc()
                .stream()
                .map(tariffMapper::toDto)
                .toList();
    }
}
