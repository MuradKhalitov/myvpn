package ru.murad.myvpn.exception;

public class SubscriptionNotFoundException extends RuntimeException {

    public SubscriptionNotFoundException(long telegramId) {
        super("Active subscription not found for Telegram user: " + telegramId);
    }
}
