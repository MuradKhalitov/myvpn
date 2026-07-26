package ru.murad.myvpn.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.murad.myvpn.config.VpnDeliveryProperties;
import ru.murad.myvpn.service.VpnDeliveryService;

@Component @RequiredArgsConstructor @ConditionalOnProperty(name = "vpn.delivery.enabled", havingValue = "true")
public class VpnDeliveryScheduler {
    private final VpnDeliveryService service; private final VpnDeliveryProperties properties;
    @Scheduled(fixedDelayString = "${vpn.delivery.fixed-delay:5s}") public void process() { service.processPendingDeliveries(properties.batchSize()); }
}
