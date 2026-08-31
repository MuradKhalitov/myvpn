package ru.murad.myvpn.scheduler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.murad.myvpn.service.VpnTrafficPolicyService;

@Component
public class VpnTrafficPolicyScheduler {
    private final VpnTrafficPolicyService service;
    public VpnTrafficPolicyScheduler(VpnTrafficPolicyService service) { this.service = service; }

    @Scheduled(fixedDelayString = "${vpn.policy.reconciliation-delay:60000}")
    public void reconcile() { service.reconcileDuePolicies(); }
}
