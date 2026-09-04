package ru.murad.myvpn.client;

public interface PhoneVerificationProvider {
    PhoneVerificationStart start(String normalizedPhone);
    PhoneVerificationState getStatus(String externalCheckId);
}
