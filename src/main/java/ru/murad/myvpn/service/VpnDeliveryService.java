package ru.murad.myvpn.service;

import java.util.Optional;

public interface VpnDeliveryService {
    VpnDeliveryWorkerResult processPendingDeliveries(int limit);
    Optional<ru.murad.myvpn.dto.VpnDeliveryMessage> loadCurrentMessage(ClaimedVpnDelivery delivery);
}
