package ru.murad.myvpn.dto;

public record TelegramButton(String text, String callbackData, String url) {

    public static TelegramButton callback(String text, String callbackData) {
        return new TelegramButton(text, callbackData, null);
    }

    public static TelegramButton url(String text, String url) {
        return new TelegramButton(text, null, url);
    }

    @Override
    public String toString() {
        return "TelegramButton[text=" + text
                + ", callbackData=" + (callbackData == null ? null : "[redacted]")
                + ", url=" + (url == null ? null : "[redacted]") + "]";
    }
}
