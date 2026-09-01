package ru.murad.myvpn.service;

public interface SubscriptionLifecycleService {
    int revokeExpiredSubscriptions();
    int deleteExpiredConfigurations();
    int recoverPendingSubscriptions();
}
