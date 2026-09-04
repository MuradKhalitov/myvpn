package ru.murad.myvpn.service;

public interface SubscriptionLifecycleService {
    int revokeExpiredSubscriptions();
    int expireTrials();
    int deleteExpiredConfigurations();
    int recoverPendingSubscriptions();
}
