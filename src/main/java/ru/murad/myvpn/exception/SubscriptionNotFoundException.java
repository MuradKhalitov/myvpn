package ru.murad.myvpn.exception;

public class SubscriptionNotFoundException extends RuntimeException {

    public SubscriptionNotFoundException(long accountId) {
        super("Active subscription not found for account: " + accountId);
    }
}
