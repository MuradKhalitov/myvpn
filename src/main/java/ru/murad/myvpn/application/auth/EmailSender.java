package ru.murad.myvpn.application.auth;

import java.time.Duration;

public interface EmailSender {

    void sendOtp(String recipient, String code, Duration ttl);
}
