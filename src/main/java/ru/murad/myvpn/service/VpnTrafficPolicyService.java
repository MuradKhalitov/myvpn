package ru.murad.myvpn.service;

import java.util.UUID;

public interface VpnTrafficPolicyService {
    int reconcileDuePolicies();
    void requestPremium(UUID accountId);
}
