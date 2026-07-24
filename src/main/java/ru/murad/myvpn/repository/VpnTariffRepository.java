package ru.murad.myvpn.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.murad.myvpn.model.VpnTariff;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VpnTariffRepository extends JpaRepository<VpnTariff, UUID> {

    List<VpnTariff> findAllByActiveTrueOrderByPriceAscDurationDaysAsc();

    Optional<VpnTariff> findByCodeAndActiveTrue(String code);
}
