package ru.murad.myvpn.service;

import jakarta.validation.Valid;
import ru.murad.myvpn.dto.RegisterTelegramUserRequest;
import ru.murad.myvpn.dto.TelegramUserDto;

public interface UserService {

    TelegramUserDto register(@Valid RegisterTelegramUserRequest request);
}
