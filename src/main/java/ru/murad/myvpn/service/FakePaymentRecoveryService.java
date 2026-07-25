package ru.murad.myvpn.service;

public interface FakePaymentRecoveryService {

    void resetLostPayment(long administratorId, long targetTelegramId);
}
