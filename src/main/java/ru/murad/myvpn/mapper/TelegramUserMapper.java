package ru.murad.myvpn.mapper;

import org.mapstruct.Mapper;
import ru.murad.myvpn.dto.TelegramUserDto;
import ru.murad.myvpn.model.TelegramUser;

@Mapper(componentModel = "spring")
public interface TelegramUserMapper {

    TelegramUserDto toDto(TelegramUser user);
}
