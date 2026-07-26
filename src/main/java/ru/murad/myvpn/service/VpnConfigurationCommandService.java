package ru.murad.myvpn.service;

import ru.murad.myvpn.dto.TelegramIncomingMessage;

public interface VpnConfigurationCommandService {
    String configurationForOwner(TelegramIncomingMessage message);
}
