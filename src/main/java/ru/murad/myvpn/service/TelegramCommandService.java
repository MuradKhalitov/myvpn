package ru.murad.myvpn.service;

import ru.murad.myvpn.dto.TelegramIncomingMessage;

public interface TelegramCommandService {

    String handle(TelegramIncomingMessage message);
}
