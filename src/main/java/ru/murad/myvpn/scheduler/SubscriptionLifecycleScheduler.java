package ru.murad.myvpn.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.murad.myvpn.service.SubscriptionLifecycleService;

@Component
@RequiredArgsConstructor
public class SubscriptionLifecycleScheduler {

    private final SubscriptionLifecycleService lifecycleService;

    @Scheduled(fixedDelayString = "${vpn.lifecycle.expiration-check-delay:60000}")
    public void revokeExpiredSubscriptions() {
        lifecycleService.revokeExpiredSubscriptions();
    }

    @Scheduled(fixedDelayString = "${vpn.lifecycle.configuration-cleanup-delay:3600000}")
    public void deleteExpiredConfigurations() {
        lifecycleService.deleteExpiredConfigurations();
    }
}
