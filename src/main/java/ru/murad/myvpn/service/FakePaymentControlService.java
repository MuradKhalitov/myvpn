package ru.murad.myvpn.service;

public interface FakePaymentControlService {

    void markSucceeded(String providerPaymentId);

    void markCanceled(String providerPaymentId);
}
