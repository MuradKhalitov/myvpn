package ru.murad.myvpn.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import ru.murad.myvpn.application.account.AccountIdentityRegistrationService;
import ru.murad.myvpn.dto.RegisterTelegramUserRequest;
import ru.murad.myvpn.dto.TelegramUserDto;
import ru.murad.myvpn.mapper.TelegramUserMapper;
import ru.murad.myvpn.repository.TelegramUserRepository;
import ru.murad.myvpn.service.UserService;

import java.time.Clock;
import java.util.Optional;

@Service
@Validated
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final TelegramUserRepository userRepository;
    private final TelegramUserMapper userMapper;
    private final AccountIdentityRegistrationService registrationService;
    private final Clock clock;

    @Override
    public TelegramUserDto register(RegisterTelegramUserRequest request) {
        return userMapper.toDto(registrationService.registerTelegramUser(
                request, clock.instant()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TelegramUserDto> findByTelegramId(long telegramId) {
        return userRepository.findByTelegramId(telegramId).map(userMapper::toDto);
    }

}
