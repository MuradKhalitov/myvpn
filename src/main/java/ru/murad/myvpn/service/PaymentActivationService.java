package ru.murad.myvpn.service;

public interface PaymentActivationService {
    PaymentActivationWorkerResult processPendingActivations(int limit);
}
