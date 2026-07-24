package ru.murad.myvpn.exception;

public class TelegramUserNotFoundException extends RuntimeException {

    public TelegramUserNotFoundException(long telegramId) {
        super("Telegram user not found: " + telegramId);
    }
}
