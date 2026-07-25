package ru.murad.myvpn.service;

import ru.murad.myvpn.dto.TelegramIncomingMessage;
import ru.murad.myvpn.dto.TelegramCallbackQuery;
import ru.murad.myvpn.dto.TelegramCommandResponse;

public interface TelegramCommandService {

    String handle(TelegramIncomingMessage message);

    TelegramCommandResponse handleResponse(TelegramIncomingMessage message);

    TelegramCommandResponse handleCallback(TelegramCallbackQuery callback);
}
