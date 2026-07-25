package ru.murad.myvpn.dto;

import java.util.List;

public record TelegramCommandResponse(
        String text,
        List<List<TelegramButton>> keyboard
) {
    public TelegramCommandResponse {
        keyboard = keyboard == null
                ? List.of()
                : keyboard.stream().map(List::copyOf).toList();
    }

    public static TelegramCommandResponse text(String text) {
        return new TelegramCommandResponse(text, List.of());
    }

    @Override
    public String toString() {
        return "TelegramCommandResponse[textLength="
                + (text == null ? 0 : text.length())
                + ", buttonCount=" + keyboard.stream().mapToInt(List::size).sum()
                + "]";
    }
}
